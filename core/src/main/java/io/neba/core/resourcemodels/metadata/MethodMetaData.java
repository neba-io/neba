/*
  Copyright 2013 the original author or authors.

  Licensed under the Apache License, Version 2.0 the "License";
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package io.neba.core.resourcemodels.metadata;

import io.neba.api.annotations.AfterMapping;
import io.neba.core.util.Annotations;

import java.lang.reflect.Method;

import static io.neba.core.util.Annotations.annotations;

/**
 * Represents method meta-data extracted from a {@link io.neba.api.annotations.ResourceModel}.
 * Used to prevent the costly retrieval of this meta-data upon each resource to model mapping.
 *
 * @author Olaf Otto
 */
public class MethodMetaData {
    private final Method method;
    private final boolean isAfterMappingCallback;

    MethodMetaData(Method method) {
        if (method == null) {
            throw new IllegalArgumentException("Constructor parameter method must not be null.");
        }
        this.method = method;
        final Annotations element = annotations(method);
        this.isAfterMappingCallback = element.contains(AfterMapping.class);
    }

    public Method getMethod() {
        return this.method;
    }

    boolean isAfterMappingCallback() {
        return this.isAfterMappingCallback;
    }

    @Override
    public int hashCode() {
        return this.method.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this ||
               (
                  obj != null && obj.getClass() == getClass() &&
                  ((MethodMetaData) obj).method.equals(this.method)
               );
    }

    @Override
    public String toString() {
        return getClass().getName() + " [" + this.method + "]";
    }
}
