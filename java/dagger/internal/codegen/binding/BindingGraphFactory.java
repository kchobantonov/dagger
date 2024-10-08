/*
 * Copyright (C) 2014 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen.binding;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.not;
import static dagger.internal.codegen.base.RequestKinds.getRequestKind;
import static dagger.internal.codegen.binding.AssistedInjectionAnnotations.isAssistedFactoryType;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.model.BindingKind.ASSISTED_INJECTION;
import static dagger.internal.codegen.model.BindingKind.DELEGATE;
import static dagger.internal.codegen.model.BindingKind.INJECTION;
import static dagger.internal.codegen.model.BindingKind.OPTIONAL;
import static dagger.internal.codegen.model.BindingKind.SUBCOMPONENT_CREATOR;
import static dagger.internal.codegen.model.RequestKind.MEMBERS_INJECTION;
import static dagger.internal.codegen.xprocessing.XTypes.isDeclared;
import static dagger.internal.codegen.xprocessing.XTypes.isTypeOf;
import static java.util.function.Predicate.isEqual;

import androidx.room.compiler.processing.XTypeElement;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import dagger.internal.codegen.base.Keys;
import dagger.internal.codegen.base.MapType;
import dagger.internal.codegen.base.OptionalType;
import dagger.internal.codegen.base.SetType;
import dagger.internal.codegen.base.TarjanSCCs;
import dagger.internal.codegen.compileroption.CompilerOptions;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.model.BindingGraph.ComponentNode;
import dagger.internal.codegen.model.BindingKind;
import dagger.internal.codegen.model.ComponentPath;
import dagger.internal.codegen.model.DaggerTypeElement;
import dagger.internal.codegen.model.DependencyRequest;
import dagger.internal.codegen.model.Key;
import dagger.internal.codegen.model.RequestKind;
import dagger.internal.codegen.model.Scope;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import javax.inject.Inject;
import javax.tools.Diagnostic;

/** A factory for {@link BindingGraph} objects. */
public final class BindingGraphFactory {
  private final LegacyBindingGraphFactory legacyBindingGraphFactory;
  private final InjectBindingRegistry injectBindingRegistry;
  private final KeyFactory keyFactory;
  private final BindingFactory bindingFactory;
  private final BindingNode.Factory bindingNodeFactory;
  private final ComponentDeclarations.Factory componentDeclarationsFactory;
  private final BindingGraphConverter bindingGraphConverter;
  private final CompilerOptions compilerOptions;

  @Inject
  BindingGraphFactory(
      LegacyBindingGraphFactory legacyBindingGraphFactory,
      InjectBindingRegistry injectBindingRegistry,
      KeyFactory keyFactory,
      BindingFactory bindingFactory,
      BindingNode.Factory bindingNodeFactory,
      ComponentDeclarations.Factory componentDeclarationsFactory,
      BindingGraphConverter bindingGraphConverter,
      CompilerOptions compilerOptions) {
    this.legacyBindingGraphFactory = legacyBindingGraphFactory;
    this.injectBindingRegistry = injectBindingRegistry;
    this.keyFactory = keyFactory;
    this.bindingFactory = bindingFactory;
    this.bindingNodeFactory = bindingNodeFactory;
    this.componentDeclarationsFactory = componentDeclarationsFactory;
    this.bindingGraphConverter = bindingGraphConverter;
    this.compilerOptions = compilerOptions;
  }

  /**
   * Creates a binding graph for a component.
   *
   * @param createFullBindingGraph if {@code true}, the binding graph will include all bindings;
   *     otherwise it will include only bindings reachable from at least one entry point
   */
  public BindingGraph create(
      ComponentDescriptor componentDescriptor, boolean createFullBindingGraph) {
    return LegacyBindingGraphFactory.useLegacyBindingGraphFactory(componentDescriptor)
        ? legacyBindingGraphFactory.create(componentDescriptor, createFullBindingGraph)
        : bindingGraphConverter.convert(
            createLegacyBindingGraph(Optional.empty(), componentDescriptor, createFullBindingGraph),
            createFullBindingGraph);
  }

  private LegacyBindingGraph createLegacyBindingGraph(
      Optional<Resolver> parentResolver,
      ComponentDescriptor componentDescriptor,
      boolean createFullBindingGraph) {
    Resolver requestResolver = new Resolver(parentResolver, componentDescriptor);

    componentDescriptor.entryPointMethods().stream()
        .map(method -> method.dependencyRequest().get())
        .forEach(
            entryPoint -> {
              if (entryPoint.kind().equals(MEMBERS_INJECTION)) {
                requestResolver.resolveMembersInjection(entryPoint.key());
              } else {
                requestResolver.resolve(entryPoint.key());
              }
            });

    if (createFullBindingGraph) {
      // Resolve the keys for all bindings in all modules, stripping any multibinding contribution
      // identifier so that the multibinding itself is resolved.
      requestResolver.declarations.allDeclarations().stream()
          // TODO(b/349155899): Consider resolving all declarations in full binding graph mode, not
          //   just those from modules.
          .filter(declaration -> declaration.contributingModule().isPresent())
          .map(Declaration::key)
          .map(Key::withoutMultibindingContributionIdentifier)
          .forEach(requestResolver::resolve);
    }

    // Resolve all bindings for subcomponents, creating subgraphs for all subcomponents that have
    // been detected during binding resolution. If a binding for a subcomponent is never resolved,
    // no BindingGraph will be created for it and no implementation will be generated. This is
    // done in a queue since resolving one subcomponent might resolve a key for a subcomponent
    // from a parent graph. This is done until no more new subcomponents are resolved.
    Set<ComponentDescriptor> resolvedSubcomponents = new HashSet<>();
    ImmutableList.Builder<LegacyBindingGraph> subgraphs = ImmutableList.builder();
    for (ComponentDescriptor subcomponent :
        Iterables.consumingIterable(requestResolver.subcomponentsToResolve)) {
      if (resolvedSubcomponents.add(subcomponent)) {
        subgraphs.add(
            createLegacyBindingGraph(
                Optional.of(requestResolver), subcomponent, createFullBindingGraph));
      }
    }

    return new LegacyBindingGraph(requestResolver, subgraphs.build());
  }

  /** Represents a fully resolved binding graph. */
  private static final class LegacyBindingGraph
      implements BindingGraphConverter.LegacyBindingGraph {
    private final Resolver resolver;
    private final ImmutableList<LegacyBindingGraph> resolvedSubgraphs;
    private final ComponentNode componentNode;

    LegacyBindingGraph(Resolver resolver, ImmutableList<LegacyBindingGraph> resolvedSubgraphs) {
      this.resolver = resolver;
      this.resolvedSubgraphs = resolvedSubgraphs;
      this.componentNode =
          ComponentNodeImpl.create(resolver.componentPath, resolver.componentDescriptor);
    }

    /** Returns the {@link ComponentNode} associated with this binding graph. */
    @Override
    public ComponentNode componentNode() {
      return componentNode;
    }

    /** Returns the {@link ComponentPath} associated with this binding graph. */
    @Override
    public ComponentPath componentPath() {
      return resolver.componentPath;
    }

    /** Returns the {@link ComponentDescriptor} associated with this binding graph. */
    @Override
    public ComponentDescriptor componentDescriptor() {
      return resolver.componentDescriptor;
    }

    /**
     * Returns the {@link ResolvedBindings} in this graph or a parent graph that matches the given
     * request.
     *
     * <p>An exception is thrown if there are no resolved bindings found for the request; however,
     * this should never happen since all dependencies should have been resolved at this point.
     */
    @Override
    public ResolvedBindings resolvedBindings(BindingRequest request) {
      return request.isRequestKind(RequestKind.MEMBERS_INJECTION)
          ? resolver.getResolvedMembersInjectionBindings(request.key())
          : resolver.getResolvedContributionBindings(request.key());
    }

    /**
     * Returns all {@link ResolvedBindings} for the given request.
     *
     * <p>Note that this only returns the bindings resolved in this component. Bindings resolved in
     * parent components are not included.
     */
    @Override
    public Iterable<ResolvedBindings> resolvedBindings() {
      // Don't return an immutable collection - this is only ever used for looping over all bindings
      // in the graph. Copying is wasteful, especially if is a hashing collection, since the values
      // should all, by definition, be distinct.
      return Iterables.concat(
          resolver.resolvedMembersInjectionBindings.values(),
          resolver.resolvedContributionBindings.values());
    }

    /** Returns the resolved subgraphs. */
    @Override
    public ImmutableList<LegacyBindingGraph> subgraphs() {
      return resolvedSubgraphs;
    }
  }

  private final class Resolver {
    final ComponentPath componentPath;
    final Optional<Resolver> parentResolver;
    final ComponentDescriptor componentDescriptor;
    final ComponentDeclarations declarations;
    final Map<Key, ResolvedBindings> resolvedContributionBindings = new LinkedHashMap<>();
    final Map<Key, ResolvedBindings> resolvedMembersInjectionBindings = new LinkedHashMap<>();
    final Deque<Key> cycleStack = new ArrayDeque<>();
    final Map<Key, Boolean> keyDependsOnMissingBindingCache = new HashMap<>();
    final Map<Key, Boolean> keyDependsOnLocalBindingsCache = new HashMap<>();
    final Queue<ComponentDescriptor> subcomponentsToResolve = new ArrayDeque<>();

    Resolver(Optional<Resolver> parentResolver, ComponentDescriptor componentDescriptor) {
      this.parentResolver = parentResolver;
      this.componentDescriptor = checkNotNull(componentDescriptor);
      DaggerTypeElement componentType = DaggerTypeElement.from(componentDescriptor.typeElement());
      componentPath =
          parentResolver.isPresent()
              ? parentResolver.get().componentPath.childPath(componentType)
              : ComponentPath.create(ImmutableList.of(componentType));
      declarations =
          componentDeclarationsFactory.create(
              parentResolver.map(parent -> parent.componentDescriptor),
              componentDescriptor);
      subcomponentsToResolve.addAll(
          componentDescriptor.childComponentsDeclaredByFactoryMethods().values());
      subcomponentsToResolve.addAll(
          componentDescriptor.childComponentsDeclaredByBuilderEntryPoints().values());
    }

    /**
     * Returns the resolved contribution bindings for the given {@link Key}:
     *
     * <ul>
     *   <li>All explicit bindings for:
     *       <ul>
     *         <li>the requested key
     *         <li>{@code Set<T>} if the requested key's type is {@code Set<Produced<T>>}
     *         <li>{@code Map<K, Provider<V>>} if the requested key's type is {@code Map<K,
     *             Producer<V>>}.
     *       </ul>
     *   <li>An implicit {@link Inject @Inject}-annotated constructor binding if there is one and
     *       there are no explicit bindings or synthetic bindings.
     * </ul>
     */
    ResolvedBindings lookUpBindings(Key requestKey) {
      Set<ContributionBinding> bindings = new LinkedHashSet<>();
      Set<ContributionBinding> multibindingContributions = new LinkedHashSet<>();
      Set<MultibindingDeclaration> multibindingDeclarations = new LinkedHashSet<>();
      Set<OptionalBindingDeclaration> optionalBindingDeclarations = new LinkedHashSet<>();
      Set<SubcomponentDeclaration> subcomponentDeclarations = new LinkedHashSet<>();

      // Gather all bindings, multibindings, optional, and subcomponent declarations/contributions.
      for (Resolver resolver : getResolverLineage()) {
        bindings.addAll(resolver.getLocalExplicitBindings(requestKey));
        multibindingContributions.addAll(resolver.getLocalMultibindingContributions(requestKey));
        multibindingDeclarations.addAll(resolver.declarations.multibindings(requestKey));
        subcomponentDeclarations.addAll(resolver.declarations.subcomponents(requestKey));
        // The optional binding declarations are keyed by the unwrapped type.
        keyFactory.unwrapOptional(requestKey)
            .map(resolver.declarations::optionalBindings)
            .ifPresent(optionalBindingDeclarations::addAll);
      }

      // Add synthetic multibinding
      if (!multibindingContributions.isEmpty() || !multibindingDeclarations.isEmpty()) {
        if (MapType.isMap(requestKey)) {
          bindings.add(bindingFactory.multiboundMap(requestKey, multibindingContributions));
        } else if (SetType.isSet(requestKey)) {
          bindings.add(bindingFactory.multiboundSet(requestKey, multibindingContributions));
        } else {
          throw new AssertionError("Unexpected type in multibinding key: " + requestKey);
        }
      }

      // Add synthetic optional binding
      if (!optionalBindingDeclarations.isEmpty()) {
        bindings.add(
            bindingFactory.syntheticOptionalBinding(
                requestKey,
                getRequestKind(OptionalType.from(requestKey).valueType()),
                lookUpBindings(keyFactory.unwrapOptional(requestKey).get()).bindings()));
      }

      // Add subcomponent creator binding
      if (!subcomponentDeclarations.isEmpty()) {
        ContributionBinding binding =
            bindingFactory.subcomponentCreatorBinding(
                ImmutableSet.copyOf(subcomponentDeclarations));
        bindings.add(binding);
        addSubcomponentToOwningResolver(binding);
      }

      // Add members injector binding
      if (isTypeOf(requestKey.type().xprocessing(), TypeNames.MEMBERS_INJECTOR)) {
        injectBindingRegistry.getOrFindMembersInjectorBinding(requestKey).ifPresent(bindings::add);
      }

      // Add Assisted Factory binding
      if (isDeclared(requestKey.type().xprocessing())
          && isAssistedFactoryType(requestKey.type().xprocessing().getTypeElement())) {
        bindings.add(
            bindingFactory.assistedFactoryBinding(
                requestKey.type().xprocessing().getTypeElement(),
                Optional.of(requestKey.type().xprocessing())));
      }

      // If there are no bindings, add the implicit @Inject-constructed binding if there is one.
      if (bindings.isEmpty()) {
        injectBindingRegistry
            .getOrFindInjectionBinding(requestKey)
            .filter(this::isCorrectlyScopedInSubcomponent)
            .ifPresent(bindings::add);
      }

      return ResolvedBindings.create(
          requestKey,
          bindings.stream()
              .map(
                  binding -> {
                    Optional<BindingNode> bindingNodeOwnedByAncestor =
                        getBindingNodeOwnedByAncestor(requestKey, binding);
                    // If a binding is owned by an ancestor we use the corresponding BindingNode
                    // instance directly rather than creating a new instance to avoid accidentally
                    // including additional multi/optional/subcomponent declarations that don't
                    // exist in the ancestor's BindingNode instance.
                    return bindingNodeOwnedByAncestor.isPresent()
                          ? bindingNodeOwnedByAncestor.get()
                          : bindingNodeFactory.forContributionBindings(
                              componentPath,
                              binding,
                              multibindingDeclarations,
                              optionalBindingDeclarations,
                              subcomponentDeclarations);
                  })
              .collect(toImmutableSet()));
    }

    /**
     * Returns true if this binding graph resolution is for a subcomponent and the {@code @Inject}
     * binding's scope correctly matches one of the components in the current component ancestry.
     * If not, it means the binding is not owned by any of the currently known components, and will
     * be owned by a future ancestor (or, if never owned, will result in an incompatibly scoped
     * binding error at the root component).
     */
    private boolean isCorrectlyScopedInSubcomponent(ContributionBinding binding) {
      checkArgument(binding.kind() == INJECTION || binding.kind() == ASSISTED_INJECTION);
      if (!rootComponent().isSubcomponent()
          || !binding.scope().isPresent()
          || binding.scope().get().isReusable()) {
        return true;
      }

      Resolver owningResolver = getOwningResolver(binding).orElse(this);
      ComponentDescriptor owningComponent = owningResolver.componentDescriptor;
      return owningComponent.scopes().contains(binding.scope().get());
    }

    private ComponentDescriptor rootComponent() {
      return parentResolver.map(Resolver::rootComponent).orElse(componentDescriptor);
    }

    /** Returns the resolved members injection bindings for the given {@link Key}. */
    ResolvedBindings lookUpMembersInjectionBinding(Key requestKey) {
      // no explicit deps for members injection, so just look it up
      Optional<MembersInjectionBinding> binding =
          injectBindingRegistry.getOrFindMembersInjectionBinding(requestKey);
      return binding.isPresent()
          ? ResolvedBindings.create(
              requestKey,
              bindingNodeFactory.forMembersInjectionBinding(componentPath, binding.get()))
          : ResolvedBindings.create(requestKey);
    }

    /**
     * When a binding is resolved for a {@link SubcomponentDeclaration}, adds corresponding {@link
     * ComponentDescriptor subcomponent} to a queue in the owning component's resolver. The queue
     * will be used to detect which subcomponents need to be resolved.
     */
    private void addSubcomponentToOwningResolver(ContributionBinding subcomponentCreatorBinding) {
      checkArgument(subcomponentCreatorBinding.kind().equals(SUBCOMPONENT_CREATOR));
      Resolver owningResolver = getOwningResolver(subcomponentCreatorBinding).get();

      XTypeElement builderType =
          subcomponentCreatorBinding.key().type().xprocessing().getTypeElement();
      owningResolver.subcomponentsToResolve.add(
          owningResolver.componentDescriptor.getChildComponentWithBuilderType(builderType));
    }

    private ImmutableSet<ContributionBinding> createDelegateBindings(
        ImmutableSet<DelegateDeclaration> delegateDeclarations) {
      ImmutableSet.Builder<ContributionBinding> builder = ImmutableSet.builder();
      for (DelegateDeclaration delegateDeclaration : delegateDeclarations) {
        builder.add(createDelegateBinding(delegateDeclaration));
      }
      return builder.build();
    }

    /**
     * Creates one (and only one) delegate binding for a delegate declaration, based on the resolved
     * bindings of the right-hand-side of a {@link dagger.Binds} method. If there are duplicate
     * bindings for the dependency key, there should still be only one binding for the delegate key.
     */
    private ContributionBinding createDelegateBinding(DelegateDeclaration delegateDeclaration) {
      Key delegateKey = delegateDeclaration.delegateRequest().key();
      if (cycleStack.contains(delegateKey)) {
        return bindingFactory.unresolvedDelegateBinding(delegateDeclaration);
      }

      ResolvedBindings resolvedDelegate;
      try {
        cycleStack.push(delegateKey);
        resolvedDelegate = lookUpBindings(delegateKey);
      } finally {
        cycleStack.pop();
      }
      if (resolvedDelegate.bindings().isEmpty()) {
        // This is guaranteed to result in a missing binding error, so it doesn't matter if the
        // binding is a Provision or Production, except if it is a @IntoMap method, in which
        // case the key will be of type Map<K, Provider<V>>, which will be "upgraded" into a
        // Map<K, Producer<V>> if it's requested in a ProductionComponent. This may result in a
        // strange error, that the RHS needs to be provided with an @Inject or @Provides
        // annotated method, but a user should be able to figure out if a @Produces annotation
        // is needed.
        // TODO(gak): revisit how we model missing delegates if/when we clean up how we model
        // binding declarations
        return bindingFactory.unresolvedDelegateBinding(delegateDeclaration);
      }
      // It doesn't matter which of these is selected, since they will later on produce a
      // duplicate binding error.
      ContributionBinding explicitDelegate =
          (ContributionBinding) resolvedDelegate.bindings().iterator().next();
      return bindingFactory.delegateBinding(delegateDeclaration, explicitDelegate);
    }

    /**
     * Returns a {@link BindingNode} for the given binding that is owned by an ancestor component,
     * if one exists. Otherwise returns {@link Optional#empty()}.
     */
    private Optional<BindingNode> getBindingNodeOwnedByAncestor(
        Key requestKey, ContributionBinding binding) {
      if (canBeResolvedInParent(requestKey, binding)) {
        // Resolve in the parent to make sure we have the most recent multi/optional contributions.
        parentResolver.get().resolve(requestKey);
        if (!requiresResolution(binding)) {
          return Optional.of(getPreviouslyResolvedBindings(requestKey).get().forBinding(binding));
        }
      }
      return Optional.empty();
    }

    private boolean canBeResolvedInParent(Key requestKey, ContributionBinding binding) {
      if (parentResolver.isEmpty()) {
        return false;
      }
      Optional<Resolver> owningResolver = getOwningResolver(binding);
      if (owningResolver.isPresent()) {
        return !owningResolver.get().equals(this);
      }
      return !Keys.isComponentOrCreator(requestKey)
          // TODO(b/305748522): Allow caching for assisted injection bindings.
          && binding.kind() != BindingKind.ASSISTED_INJECTION
          && getPreviouslyResolvedBindings(requestKey).isPresent()
          && getPreviouslyResolvedBindings(requestKey).get().bindings().contains(binding);
    }

    private Optional<Resolver> getOwningResolver(ContributionBinding binding) {
      // TODO(ronshapiro): extract the different pieces of this method into their own methods
      if ((binding.scope().isPresent() && binding.scope().get().isProductionScope())
          || binding.kind().equals(BindingKind.PRODUCTION)) {
        for (Resolver requestResolver : getResolverLineage()) {
          // Resolve @Inject @ProductionScope bindings at the highest production component.
          if (binding.kind().equals(INJECTION)
              && requestResolver.componentDescriptor.isProduction()) {
            return Optional.of(requestResolver);
          }

          // Resolve explicit @Produces and @ProductionScope bindings at the highest component that
          // installs the binding.
          if (requestResolver.containsExplicitBinding(binding)) {
            return Optional.of(requestResolver);
          }
        }
      }

      if (binding.scope().isPresent() && binding.scope().get().isReusable()) {
        for (Resolver requestResolver : getResolverLineage().reverse()) {
          // If a @Reusable binding was resolved in an ancestor, use that component.
          ResolvedBindings resolvedBindings =
              requestResolver.resolvedContributionBindings.get(binding.key());
          if (resolvedBindings != null && resolvedBindings.bindings().contains(binding)) {
            return Optional.of(requestResolver);
          }
        }
        // If a @Reusable binding was not resolved in any ancestor, resolve it here.
        return Optional.empty();
      }

      // TODO(b/359893922): we currently iterate from child to parent to find an owning resolver,
      // but we probably want to iterate from parent to child to catch missing bindings in
      // misconfigured repeated modules.
      for (Resolver requestResolver : getResolverLineage().reverse()) {
        if (requestResolver.containsExplicitBinding(binding)) {
          return Optional.of(requestResolver);
        }
      }

      // look for scope separately.  we do this for the case where @Singleton can appear twice
      // in the † compatibility mode
      Optional<Scope> bindingScope = binding.scope();
      if (bindingScope.isPresent()) {
        for (Resolver requestResolver : getResolverLineage().reverse()) {
          if (requestResolver.componentDescriptor.scopes().contains(bindingScope.get())) {
            return Optional.of(requestResolver);
          }
        }
      }
      return Optional.empty();
    }

    private boolean containsExplicitBinding(ContributionBinding binding) {
      return declarations.bindings(binding.key()).contains(binding)
          || resolverContainsDelegateDeclarationForBinding(binding)
          || !declarations.subcomponents(binding.key()).isEmpty();
    }

    /** Returns true if {@code binding} was installed in a module in this resolver's component. */
    private boolean resolverContainsDelegateDeclarationForBinding(ContributionBinding binding) {
      if (!binding.kind().equals(DELEGATE)) {
        return false;
      }

      // Map multibinding key values are wrapped with a framework type. This needs to be undone
      // to look it up in the delegate declarations map.
      // TODO(erichang): See if we can standardize the way map keys are used in these data
      // structures, either always wrapped or unwrapped to be consistent and less errorprone.
      Key bindingKey =
          LegacyBindingGraphFactory.useStrictMultibindings(compilerOptions, binding)
              ? keyFactory.unwrapMapValueType(binding.key())
              : binding.key();

      return declarations.delegates(bindingKey).stream()
          .anyMatch(
              declaration ->
                  declaration.contributingModule().equals(binding.contributingModule())
                  && declaration.bindingElement().equals(binding.bindingElement()));
    }

    /** Returns the resolver lineage from parent to child. */
    private ImmutableList<Resolver> getResolverLineage() {
      ImmutableList.Builder<Resolver> resolverList = ImmutableList.builder();
      for (Optional<Resolver> currentResolver = Optional.of(this);
          currentResolver.isPresent();
          currentResolver = currentResolver.get().parentResolver) {
        resolverList.add(currentResolver.get());
      }
      return resolverList.build().reverse();
    }

    /**
     * Returns the explicit {@link ContributionBinding}s that match the {@code key} from this
     * resolver.
     */
    private ImmutableSet<ContributionBinding> getLocalExplicitBindings(Key key) {
      return ImmutableSet.<ContributionBinding>builder()
          .addAll(declarations.bindings(key))
          // @Binds @IntoMap declarations have key Map<K, V>, unlike @Provides @IntoMap or @Produces
          // @IntoMap, which have Map<K, Provider/Producer<V>> keys. So unwrap the key's type's
          // value type if it's a Map<K, Provider/Producer<V>> before looking in
          // delegate declarations. createDelegateBindings() will create bindings with the properly
          // wrapped key type.
          .addAll(
              createDelegateBindings(declarations.delegates(keyFactory.unwrapMapValueType(key))))
          .build();
    }

    /**
     * Returns the explicit multibinding contributions that contribute to the map or set requested
     * by {@code key} from this resolver.
     */
    private ImmutableSet<ContributionBinding> getLocalMultibindingContributions(Key key) {
      return ImmutableSet.<ContributionBinding>builder()
          .addAll(declarations.multibindingContributions(key))
          .addAll(createDelegateBindings(declarations.delegateMultibindingContributions(key)))
          .build();
    }

    /**
     * Returns the {@link OptionalBindingDeclaration}s that match the {@code key} from this and all
     * ancestor resolvers.
     */
    private ImmutableSet<OptionalBindingDeclaration> getOptionalBindingDeclarations(Key key) {
      Optional<Key> unwrapped = keyFactory.unwrapOptional(key);
      if (unwrapped.isEmpty()) {
        return ImmutableSet.of();
      }
      ImmutableSet.Builder<OptionalBindingDeclaration> declarations = ImmutableSet.builder();
      for (Resolver resolver : getResolverLineage()) {
        declarations.addAll(resolver.declarations.optionalBindings(unwrapped.get()));
      }
      return declarations.build();
    }

    /**
     * Returns the {@link ResolvedBindings} for {@code key} that was resolved in this resolver or an
     * ancestor resolver. Only checks for {@link ContributionBinding}s as {@link
     * MembersInjectionBinding}s are not inherited.
     */
    private Optional<ResolvedBindings> getPreviouslyResolvedBindings(Key key) {
      if (parentResolver.isEmpty()) {
        return Optional.empty();
      }
      // Check the parent's resolvedContributionBindings directly before calling
      // parentResolver.getPreviouslyResolvedBindings() otherwise the parent will skip itself.
      return parentResolver.get().resolvedContributionBindings.containsKey(key)
          ? Optional.of(parentResolver.get().resolvedContributionBindings.get(key))
          : parentResolver.get().getPreviouslyResolvedBindings(key);
    }

    private void resolveMembersInjection(Key key) {
      ResolvedBindings bindings = lookUpMembersInjectionBinding(key);
      resolveDependencies(bindings);
      resolvedMembersInjectionBindings.put(key, bindings);
    }

    void resolve(Key key) {
      // If we find a cycle, stop resolving. The original request will add it with all of the
      // other resolved deps.
      if (cycleStack.contains(key)) {
        return;
      }

      // If the binding was previously resolved in this (sub)component, don't resolve it again.
      if (resolvedContributionBindings.containsKey(key)) {
        return;
      }

      cycleStack.push(key);
      try {
        ResolvedBindings bindings = lookUpBindings(key);
        resolvedContributionBindings.put(key, bindings);
        resolveDependencies(bindings);
      } finally {
        cycleStack.pop();
      }
    }

    /**
     * {@link #resolve(Key) Resolves} each of the dependencies of the bindings owned by this
     * component.
     */
    private void resolveDependencies(ResolvedBindings resolvedBindings) {
      for (BindingNode binding : resolvedBindings.bindingNodesOwnedBy(componentPath)) {
        for (DependencyRequest dependency : binding.dependencies()) {
          resolve(dependency.key());
        }
      }
    }

    private ResolvedBindings getResolvedContributionBindings(Key key) {
      if (resolvedContributionBindings.containsKey(key)) {
        return resolvedContributionBindings.get(key);
      }
      if (parentResolver.isPresent()) {
        return parentResolver.get().getResolvedContributionBindings(key);
      }
      throw new AssertionError("No resolved bindings for key: " + key);
    }

    private ResolvedBindings getResolvedMembersInjectionBindings(Key key) {
      return resolvedMembersInjectionBindings.get(key);
    }

    private boolean requiresResolution(Binding binding) {
      return new RequiresResolutionChecker().requiresResolution(binding);
    }

    private final class RequiresResolutionChecker {
      // Note: to keep things simpler, we only have a cache for "Key". For "Binding" we check the
      // binding itself for local bindings, then we rely on the cache for checking all of its
      // dependencies.
      private boolean requiresResolution(Binding binding) {
        // If we're not allowed to float then the binding cannot be re-resolved in this component.
        if (isNotAllowedToFloat(binding)) {
          return false;
        }
        return hasLocalBindings(binding)
            || (shouldCheckDependencies(binding)
                && binding.dependencies().stream()
                    .map(DependencyRequest::key)
                    .anyMatch(this::requiresResolution));
      }

      private boolean requiresResolution(Key key) {
        // Try to re-resolving bindings that depend on missing bindings. The missing bindings
        // will still end up being reported for cases where the binding is not allowed to float,
        // but re-resolving allows cases that are allowed to float to be re-resolved which can
        // prevent misleading dependency traces that include all floatable bindings.
        // E.g. see MissingBindingSuggestionsTest#bindsMissingBinding_fails().
        return dependsOnLocalBinding(key) || dependsOnMissingBinding(key);
      }

      private boolean isNotAllowedToFloat(Binding binding) {
        // In general, @Provides/@Binds/@Production bindings are allowed to float to get access to
        // multibinding contributions that are contributed in subcomponents. However, they aren't
        // allowed to float to get access to missing bindings that are installed in subcomponents,
        // so we prevent floating if these bindings depend on a missing binding.
        return (binding.kind() != BindingKind.INJECTION
                && binding.kind() != BindingKind.ASSISTED_INJECTION)
            && dependsOnMissingBinding(binding.key());
      }

      private boolean dependsOnMissingBinding(Key key) {
        if (!keyDependsOnMissingBindingCache.containsKey(key)) {
          visitUncachedDependencies(key);
        }
        return checkNotNull(keyDependsOnMissingBindingCache.get(key));
      }

      private boolean dependsOnLocalBinding(Key key) {
        if (!keyDependsOnLocalBindingsCache.containsKey(key)) {
          visitUncachedDependencies(key);
        }
        return checkNotNull(keyDependsOnLocalBindingsCache.get(key));
      }

      private void visitUncachedDependencies(Key requestKey) {
        // We use Tarjan's algorithm to visit the uncached dependencies of the requestKey grouped by
        // strongly connected components (i.e. cycles) and iterated in reverse topological order.
        for (ImmutableSet<Key> cycleKeys : stronglyConnectedComponents(requestKey)) {
          // As a sanity check, verify that none of the keys in the cycle are cached yet.
          checkState(cycleKeys.stream().noneMatch(keyDependsOnLocalBindingsCache::containsKey));

          ImmutableSet<ResolvedBindings> cycleBindings =
              cycleKeys.stream().map(this::previouslyResolvedBindings).collect(toImmutableSet());

          checkState(cycleKeys.stream().noneMatch(keyDependsOnMissingBindingCache::containsKey));
          boolean dependsOnMissingBinding =
              cycleBindings.stream().anyMatch(ResolvedBindings::isEmpty)
              || cycleBindings.stream()
                  .map(ResolvedBindings::bindings)
                  .flatMap(ImmutableCollection::stream)
                  .filter(this::shouldCheckDependencies)
                  .flatMap(binding -> binding.dependencies().stream())
                  .map(DependencyRequest::key)
                  .filter(not(cycleKeys::contains))
                  .anyMatch(keyDependsOnMissingBindingCache::get);
          // All keys in the cycle have the same cached value since they all depend on each other.
          cycleKeys.forEach(
              key -> keyDependsOnMissingBindingCache.put(key, dependsOnMissingBinding));

          // Note that we purposely don't filter out scoped bindings below. In particular, there are
          // currently 3 cases where hasLocalBinding will return true:
          //
          //   1) The binding is MULTIBOUND_SET/MULTIBOUND_MAP and depends on an explicit
          //      multibinding contributions in the current component.
          //   2) The binding is OPTIONAL and depends on an explicit binding contributed in the
          //      current component.
          //   3) The binding has a duplicate explicit binding contributed in this component.
          //
          // For case #1 and #2 it's not actually required to check for scope because those are
          // synthetic bindings which are never scoped.
          //
          // For case #3 we actually want don't want to rule out a scoped binding, e.g. in the case
          // where we have a floating @Inject Foo(Bar bar) binding with @Singleton Bar provided in
          // the ParentComponent and a duplicate Bar provided in the ChildComponent we want to
          // reprocess Foo so that we can report the duplicate Bar binding.
          boolean dependsOnLocalBindings =
              // First, check if any of the bindings themselves depends on local bindings.
              cycleBindings.stream().anyMatch(Resolver.this::hasLocalBindings)
              // Next, check if any of the dependencies (that aren't in the cycle itself) depend
              // on local bindings. We should be guaranteed that all dependencies are cached since
              // Tarjan's algorithm is traversed in reverse topological order.
              || cycleBindings.stream()
                  .map(ResolvedBindings::bindings)
                  .flatMap(ImmutableCollection::stream)
                  .filter(this::shouldCheckDependencies)
                  .flatMap(binding -> binding.dependencies().stream())
                  .map(DependencyRequest::key)
                  .filter(not(cycleKeys::contains))
                  .anyMatch(keyDependsOnLocalBindingsCache::get);

          // All keys in the cycle have the same cached value since they all depend on each other.
          cycleKeys.forEach(key -> keyDependsOnLocalBindingsCache.put(key, dependsOnLocalBindings));
        }
      }

      /**
       * Returns a list of strongly connected components in reverse topological order, starting from
       * the given {@code requestKey} and traversing its transitive dependencies.
       *
       * <p>Note that the returned list may not include all transitive dependencies of the {@code
       * requestKey} because we intentionally stop at dependencies that:
       *
       * <ul>
       *   <li> Already have a cached value.
       *   <li> Are scoped to an ancestor component (i.e. cannot depend on local bindings).
       *   <li> Have a direct dependency on local bindings (i.e. no need to traverse further).
       * </ul>
       */
      private ImmutableList<ImmutableSet<Key>> stronglyConnectedComponents(Key requestKey) {
        Set<Key> uncachedKeys = new LinkedHashSet<>();
        SetMultimap<Key, Key> successorsFunction = LinkedHashMultimap.create();
        Deque<Key> queue = new ArrayDeque<>();
        queue.add(requestKey);
        while (!queue.isEmpty()) {
          Key key = queue.pop();
          if (keyDependsOnLocalBindingsCache.containsKey(key) || !uncachedKeys.add(key)) {
            continue;
          }
          previouslyResolvedBindings(key).bindings().stream()
              .filter(this::shouldCheckDependencies)
              .flatMap(binding -> binding.dependencies().stream())
              .forEach(
                  dependency -> {
                    queue.push(dependency.key());
                    successorsFunction.put(key, dependency.key());
                  });
        }
        return TarjanSCCs.compute(
            ImmutableSet.copyOf(uncachedKeys),
            node -> successorsFunction.get(node).stream()
                // We added successors eagerly in the while-loop above but they don't actually need
                // to be visited unless they are contained within the set of uncachedKeys so filter.
                .filter(uncachedKeys::contains)
                .collect(toImmutableSet()));
      }

      private ResolvedBindings previouslyResolvedBindings(Key key) {
        Optional<ResolvedBindings> previouslyResolvedBindings = getPreviouslyResolvedBindings(key);
        checkArgument(
            previouslyResolvedBindings.isPresent(),
            "no previously resolved bindings in %s for %s",
            Resolver.this,
            key);
        return previouslyResolvedBindings.get();
      }

      private boolean shouldCheckDependencies(Binding binding) {
        // Note: we can skip dependencies for scoped bindings because while there could be
        // duplicates underneath the scoped binding, those duplicates are technically unused so
        // Dagger shouldn't validate them.
        return !isScopedToComponent(binding)
            // TODO(beder): Figure out what happens with production subcomponents.
            && !binding.kind().equals(BindingKind.PRODUCTION);
      }

      private boolean isScopedToComponent(Binding binding) {
        return binding.scope().isPresent() && !binding.scope().get().isReusable();
      }
    }

    private boolean hasLocalBindings(Binding binding) {
      return hasLocalMultibindingContributions(binding.key())
          || hasDuplicateExplicitBinding(binding)
          || hasLocalOptionalBindingContribution(
              binding.key(), ImmutableSet.of((ContributionBinding) binding));
    }

    private boolean hasLocalBindings(ResolvedBindings resolvedBindings) {
      return hasLocalMultibindingContributions(resolvedBindings.key())
          || hasDuplicateExplicitBinding(resolvedBindings)
          || hasLocalOptionalBindingContribution(resolvedBindings);
    }

    /**
     * Returns {@code true} if there is at least one multibinding contribution declared within
     * this component's modules that matches the key.
     */
    private boolean hasLocalMultibindingContributions(Key requestKey) {
      return !declarations.multibindingContributions(requestKey).isEmpty()
          || !declarations.delegateMultibindingContributions(requestKey).isEmpty();
    }

    /**
     * Returns {@code true} if there is a contribution in this component for an {@code
     * Optional<Foo>} key that has not been contributed in a parent.
     */
    private boolean hasLocalOptionalBindingContribution(ResolvedBindings resolvedBindings) {
      return hasLocalOptionalBindingContribution(
          resolvedBindings.key(), resolvedBindings.bindings());
    }

    private boolean hasLocalOptionalBindingContribution(
          Key key, ImmutableSet<? extends Binding> previouslyResolvedBindings) {
      if (previouslyResolvedBindings.stream()
          .map(Binding::kind)
          .anyMatch(isEqual(OPTIONAL))) {
        return hasLocalExplicitBindings(keyFactory.unwrapOptional(key).get());
      } else {
        // If a parent contributes a @Provides Optional<Foo> binding and a child has a
        // @BindsOptionalOf Foo method, the two should conflict, even if there is no binding for
        // Foo on its own
        return !getOptionalBindingDeclarations(key).isEmpty();
      }
    }

    /**
     * Returns {@code true} if there is at least one explicit binding that matches the given key.
     */
    private boolean hasLocalExplicitBindings(Key requestKey) {
      return !declarations.bindings(requestKey).isEmpty()
          || !declarations.delegates(keyFactory.unwrapMapValueType(requestKey)).isEmpty();
    }

    /** Returns {@code true} if this resolver has a duplicate explicit binding to resolve. */
    private boolean hasDuplicateExplicitBinding(ResolvedBindings previouslyResolvedBindings) {
      return hasDuplicateExplicitBinding(
          previouslyResolvedBindings.key(), previouslyResolvedBindings.bindings());
    }

    /** Returns {@code true} if this resolver has a duplicate explicit binding to resolve. */
    private boolean hasDuplicateExplicitBinding(Binding binding) {
      return hasDuplicateExplicitBinding(binding.key(), ImmutableSet.of(binding));
    }

    /** Returns {@code true} if this resolver has a duplicate explicit binding to resolve. */
    private boolean hasDuplicateExplicitBinding(
          Key key, ImmutableSet<? extends Binding> previouslyResolvedBindings) {
      // By default, we don't actually report an error when an explicit binding tries to override
      // an injection binding (b/312202845). For now, ignore injection bindings unless we actually
      // will report an error, otherwise we'd end up silently overriding the binding rather than
      // reporting a duplicate.
      // TODO(b/312202845): This can be removed once b/312202845 is fixed.
      if (!compilerOptions.explicitBindingConflictsWithInjectValidationType()
          .diagnosticKind()
          .equals(Optional.of(Diagnostic.Kind.ERROR))) {
        previouslyResolvedBindings =
            previouslyResolvedBindings.stream()
                .filter(binding -> !binding.kind().equals(BindingKind.INJECTION))
                .collect(toImmutableSet());
      }

      // If the previously resolved bindings aren't empty and they don't contain all of the local
      // explicit bindings then the current component must contain a duplicate explicit binding.
      return !previouslyResolvedBindings.isEmpty() && hasLocalExplicitBindings(key);
    }
  }
}
