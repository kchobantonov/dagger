/*
 * Copyright (C) 2017 The Dagger Authors.
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

package dagger.internal.codegen.writing;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.internal.codegen.binding.ComponentBinding;
import dagger.internal.codegen.javapoet.Expression;

/** A binding expression for the instance of the component itself, i.e. {@code this}. */
final class ComponentInstanceRequestRepresentation extends RequestRepresentation {
  private final ComponentImplementation componentImplementation;
  private final ComponentBinding binding;

  @AssistedInject
  ComponentInstanceRequestRepresentation(
      @Assisted ComponentBinding binding, ComponentImplementation componentImplementation) {
    this.componentImplementation = componentImplementation;
    this.binding = binding;
  }

  @Override
  Expression getDependencyExpression(ClassName requestingClass) {
    return Expression.create(
        binding.key().type().xprocessing(),
        componentImplementation.name().equals(requestingClass)
            ? CodeBlock.of("this")
            : componentImplementation.componentFieldReference());
  }

  @AssistedFactory
  static interface Factory {
    ComponentInstanceRequestRepresentation create(ComponentBinding binding);
  }
}
