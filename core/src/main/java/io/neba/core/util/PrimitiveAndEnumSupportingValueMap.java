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

package io.neba.core.util;

import org.apache.sling.api.resource.ValueMap;

import javax.annotation.Nonnull;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.lang3.ClassUtils.primitiveToWrapper;

/**
 * This {@link ValueMap} decorator converts primitive types passed to {@link #get(String, Class)} or
 * {@link #get(String, Object)} to their boxed equivalents prior to their retrieval from the
 * wrapped value map, as the standard value map does not support primitive type retrieval.
 *
 * @author Olaf Otto
 */
public class PrimitiveAndEnumSupportingValueMap implements ValueMap {
    private final ValueMap map;

    /**
     * @param valueMap must not be <code>null</code>.
     */
    public PrimitiveAndEnumSupportingValueMap(ValueMap valueMap) {
        if (valueMap == null) {
            throw new IllegalArgumentException("Method argument valueMap must not be null.");
        }
        this.map = valueMap;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(@Nonnull String name, @Nonnull Class<T> type) {
        if (name == null) {
            throw new IllegalArgumentException("Method argument name must not be null.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Method argument type must not be null.");
        }

        if (type.isEnum()) {
            return getEnumInstance(name, (Class<Enum>) type);
        }

        if (type.isArray() && type.getComponentType().isEnum()) {
            return getEnumInstanceArray(name, type);
        }

        final Class<?> boxedType = primitiveToWrapper(type);
        return (T) this.map.get(name, boxedType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T getEnumInstance(@Nonnull String name, @Nonnull Class<Enum> type) {
        String instanceName = this.map.get(name, String.class);
        if (instanceName == null) {
            return null;
        }

        try {
            return (T) Enum.valueOf(type, instanceName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T getEnumInstanceArray(@Nonnull String name, @Nonnull Class<T> type) {
        String[] instanceNames = this.map.get(name, String[].class);
        if (instanceNames == null) {
            return null;
        }
        Enum[] enumInstances = (Enum[]) Array.newInstance(type.getComponentType(), instanceNames.length);

        int numberOfEnumInstances = 0;
        for (String instanceName : instanceNames) {
            try {
                enumInstances[numberOfEnumInstances] = Enum.valueOf((Class<Enum>) type.getComponentType(), instanceName);
                ++numberOfEnumInstances;
            } catch (IllegalArgumentException e) {
                // Ignore; continue.
            }
        }
        if (numberOfEnumInstances == enumInstances.length) {
            return (T) enumInstances;
        }

        Enum[] effectiveInstances = (Enum[]) Array.newInstance(type.getComponentType(), numberOfEnumInstances);
        System.arraycopy(enumInstances, 0, effectiveInstances, 0, effectiveInstances.length);
        return (T) effectiveInstances;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(@Nonnull String name, T defaultValue) {
        if (name == null) {
            throw new IllegalArgumentException("Method argument name must not be null.");
        }
        if (defaultValue == null) {
            throw new IllegalArgumentException("Method argument defaultValue must not be null.");
        }

        T t = (T) get(name, defaultValue.getClass());

        return t == null ? defaultValue : t;
    }

    @Override
    public Object get(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Method argument key must not be null.");
        }

        return this.map.get(key);
    }

    @Override
    public int size() {
        return this.map.size();
    }

    @Override
    public boolean isEmpty() {
        return this.map.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return this.map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return this.map.containsValue(value);
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(@Nonnull Map<? extends String, ?> m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    @Nonnull
    public Set<String> keySet() {
        return this.map.keySet();
    }

    @Override
    @Nonnull
    public Collection<Object> values() {
        return this.map.values();
    }

    @Override
    @Nonnull
    public Set<Entry<String, Object>> entrySet() {
        return this.map.entrySet();
    }
}
