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

import io.neba.api.annotations.Children;
import io.neba.api.annotations.Path;
import io.neba.api.annotations.Reference;
import io.neba.api.annotations.This;
import io.neba.api.resourcemodels.Lazy;
import io.neba.core.util.Annotations;
import io.neba.core.util.ReflectionUtil;
import io.neba.core.util.ResourcePaths;
import net.bytebuddy.ByteBuddy;
import org.apache.commons.lang3.ClassUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.Callable;

import static io.neba.core.util.Annotations.annotations;
import static io.neba.core.util.ReflectionUtil.getInstantiableCollectionTypes;
import static io.neba.core.util.ReflectionUtil.makeAccessible;
import static io.neba.core.util.ReflectionUtil.getBoundaryOfParametrizedType;
import static io.neba.core.util.ResourcePaths.path;
import static net.bytebuddy.description.modifier.Visibility.PRIVATE;
import static net.bytebuddy.implementation.FieldAccessor.ofField;
import static net.bytebuddy.implementation.InvocationHandlerAdapter.toField;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.join;
import static org.apache.commons.lang3.reflect.TypeUtils.getRawType;

/**
 * Represents meta-data of a mappable {@link io.neba.api.annotations.ResourceModel resource model} field.
 * Used to prevent the costly retrieval of this meta-data upon each resource to model mapping.
 *
 * @author Olaf Otto
 */
public class MappedFieldMetaData {
    private boolean isLazy;

    /**
     * Whether a property cannot be represented by a resource but must stem
     * from a value map representing the properties of a resource.
     */
    private static boolean isPropertyType(Class<?> type) {
        return type.isPrimitive() ||
                type == String.class ||
                type == Date.class ||
                type == Calendar.class ||
                ClassUtils.wrapperToPrimitive(type) != null;
    }

    private final Field field;
    private final Annotations annotations;
    private final ResourcePaths.ResourcePath path;
    private final boolean isReference;
    private final boolean isAppendPathPresentOnReference;
    private final String appendPathOnReference;
    private final boolean isThisReference;
    private final boolean isPathAnnotationPresent;
    private final boolean isPropertyType;
    private final boolean isCollectionType;
    private final boolean isInstantiableCollectionType;
    private final boolean isChildrenAnnotationPresent;
    private final boolean isResolveBelowEveryChildPathPresentOnChildren;
    private final String resolveBelowEveryChildPathOnChildren;

    private final Class<?> typeParameter;
    private final Class<?> arrayTypeOfComponentType;
    private final Type genericFieldType;
    private final Class<?> fieldType;
    private final Class<?> modelType;
    private final Class<? extends NebaDelegatingLazyLoadingProxy> collectionProxyFactory;

    /**
     * Immediately extracts all metadata for the provided field.
     *
     * @param field must not be <code>null</code>.
     */
    public MappedFieldMetaData(Field field, Class<?> modelType) {
        if (field == null) {
            throw new IllegalArgumentException("Constructor parameter field must not be null.");
        }
        if (modelType == null) {
            throw new IllegalArgumentException("Method argument modelType must not be null.");
        }

        // Atomic initialization
        this.modelType = modelType;
        this.field = field;
        this.isLazy = field.getType() == Lazy.class;
        this.annotations = annotations(field);

        // Treat Lazy<X> fields transparently like X fields: This way, anyone operating on the metadata is not
        // forced to be aware of the lazy-loading value holder indirection but can operate on the target type directly.
        this.genericFieldType = this.isLazy ? getParameterTypeOf(field.getGenericType()) : field.getGenericType();
        this.fieldType = this.isLazy ? getRawType(this.genericFieldType, this.modelType) : field.getType();
        this.isCollectionType = Collection.class.isAssignableFrom(this.fieldType);
        this.isPathAnnotationPresent = this.annotations.contains(Path.class);
        this.isReference = this.annotations.contains(Reference.class);
        this.isThisReference = this.annotations.contains(This.class);
        this.isChildrenAnnotationPresent = this.annotations.contains(Children.class);

        // The following initializations are not atomic but order-sensitive.
        this.isAppendPathPresentOnReference = isAppendPathPresentOnReferenceInternal();
        this.appendPathOnReference = getAppendPathFromReference();
        this.isResolveBelowEveryChildPathPresentOnChildren = isResolveBelowEveryChildPathPresentOnChildrenInternal();
        this.resolveBelowEveryChildPathOnChildren = getResolveBelowEveryChildPathFromChildren();
        this.typeParameter = resolveTypeParameter();
        this.arrayTypeOfComponentType = resolveArrayTypeOfComponentType();
        this.path = getPathInternal();
        this.isPropertyType = isPropertyTypeInternal();
        this.isInstantiableCollectionType = ReflectionUtil.isInstantiableCollectionType(this.fieldType);

        enforceInstantiableCollectionTypeForExplicitlyMappedFields();
        this.collectionProxyFactory = prepareProxyFactoryForCollectionTypes();

        makeAccessible(field);
    }

    /**
     * Wraps {@link io.neba.core.util.ReflectionUtil#getBoundaryOfParametrizedType(java.lang.reflect.Type, java.lang.reflect.Type)}
     * in order to provide a field-related error message to signal users which field is affected.
     */
    private Type getParameterTypeOf(Type type) {
        try {
            return getBoundaryOfParametrizedType(type, this.modelType);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to resolve a generic parameter type of the mapped field " + this.field + ".", e);
        }
    }

    /**
     * If the field type is eligible for automated lazy-loading, prepare a proxy class for the field type that delegates
     * all method calls to the {@link NebaLazyLoadingHandler}. To associate this handler with instances of the generated proxy class,
     * a special field and interface are provided to allow injection of the handlers into the instances. The infrastructure interfaces must
     * be public as ByteBuddy enhances a publicly visible type and adding inaccessible interfaces to it would break any reflective action
     * occurring outside of the private namespace of the interfaces.
     */
    @SuppressWarnings("unchecked")
    private Class<? extends NebaDelegatingLazyLoadingProxy> prepareProxyFactoryForCollectionTypes() {
        if (this.isInstantiableCollectionType) {
            return (Class<? extends NebaDelegatingLazyLoadingProxy>) new ByteBuddy()
                    .subclass(this.fieldType) // Enhance the field type by subclassing it.
                    .defineField("__neba__lazyLoading_handler", InvocationHandler.class, PRIVATE) // Create a private member that will contain the lazy loading handler.
                    .implement(NebaDelegatingLazyLoadingProxy.class) // We will inject the lazy loading handler using this interface.
                    .intercept(ofField("__neba__lazyLoading_handler")) // When the interface's setter method is called, store the argument in this member.
                    .method(not(isDeclaredBy(NebaDelegatingLazyLoadingProxy.class))) // Intercept all method calls of fieldType, except for those defined by the internal interface.
                    .intercept(toField("__neba__lazyLoading_handler")) // Delegate all intercepted method calls to the this member
                    .make()
                    .load(getClass().getClassLoader())
                    .getLoaded();
        }
        return null;
    }

    public Object getLazyLoadingProxy(Callable<?> valueFactory) {
        NebaDelegatingLazyLoadingProxy proxy;
        try {
            proxy = this.collectionProxyFactory.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to instantiate a lazy loading proxy for field type " + this.fieldType + ".", e);
        }
        proxy.setHandler(new NebaLazyLoadingHandler(valueFactory));
        return proxy;
    }

    private String getAppendPathFromReference() {
        return this.isAppendPathPresentOnReference ? getAppendPathOfReference() : null;
    }

    private boolean isAppendPathPresentOnReferenceInternal() {
        return isReference && !isBlank(getAppendPathOfReference());
    }

    private String getAppendPathOfReference() {
        String path = this.annotations.get(Reference.class).append();
        if (!isEmpty(path) && path.charAt(0) != '/') {
            path = '/' + path;
        }
        return path;
    }

    private boolean isResolveBelowEveryChildPathPresentOnChildrenInternal() {
        return this.isChildrenAnnotationPresent && !isBlank(getResolveBelowEveryChildPathOfChildren());
    }

    private String getResolveBelowEveryChildPathFromChildren() {
        return this.isResolveBelowEveryChildPathPresentOnChildren ?
                getResolveBelowEveryChildPathOfChildren() : null;
    }

    private String getResolveBelowEveryChildPathOfChildren() {
        String relativePath = this.annotations.get(Children.class).resolveBelowEveryChild();
        // The path must be relative, otherwise resource#getChild will be equivalent to
        // resolver.getResource("/..."), i.e. the resolution will not be relative.
        return isResolveBelowEveryChildPathPresentOnChildren &&
                relativePath.charAt(0) == '/' ? relativePath.substring(1) : relativePath;
    }

    private Class<?> resolveTypeParameter() {
        Class<?> typeParameter = null;
        if (this.isCollectionType) {
            typeParameter = getRawType(getParameterTypeOf(this.genericFieldType), this.modelType);
        } else if (getType().isArray()) {
            typeParameter = getType().getComponentType();
        }
        return typeParameter;
    }

    private Class<?> resolveArrayTypeOfComponentType() {
        if (this.typeParameter != null) {
            return Array.newInstance(typeParameter, 0).getClass();
        }
        return null;
    }


    private void enforceInstantiableCollectionTypeForExplicitlyMappedFields() {
        if (((this.isReference && this.isCollectionType) || this.isChildrenAnnotationPresent)
                && !this.isInstantiableCollectionType) {
            throw new IllegalArgumentException("Unsupported type of field " +
                    this.field + ": Only " + join(getInstantiableCollectionTypes(), ", ") + " are supported.");
        }
    }

    /**
     * Determines the relative or absolute JCR path of a field.
     * The path is derived from either the field name (this is the default)
     * or from an explicit {@link io.neba.api.annotations.Path} annotation.
     */
    private ResourcePaths.ResourcePath getPathInternal() {
        String resolvedPath;
        if (isPathAnnotationPresent()) {
            Path path = this.annotations.get(Path.class);
            if (isBlank(path.value())) {
                throw new IllegalArgumentException("The value of the @" + Path.class.getSimpleName() +
                        " annotation on " + field + " must not be empty");
            }
            resolvedPath = path.value();
        } else {
            resolvedPath = field.getName();
        }
        return path(resolvedPath);
    }

    /**
     * Determines whether the {@link java.lang.reflect.Field#getType() field type}
     * can only be represented by a property of a {@link org.apache.sling.api.resource.Resource},
     * not a {@link org.apache.sling.api.resource.Resource} itself.
     */
    private boolean isPropertyTypeInternal() {
        Class<?> type = getType();
        return
                // References are always contained in properties of type String or String[].
                isReference()
                        || isPropertyType(type)
                        || (type.isArray() || isCollectionType) && isPropertyType(getTypeParameter());
    }

    /**
     * @return The {@link java.lang.reflect.Field#setAccessible(boolean) accessible}
     * {@link java.lang.reflect.Field} represented by this meta data.
     */
    public Field getField() {
        return this.field;
    }

    /**
     * @return Whether this field is annotated with {@link io.neba.api.annotations.Reference}.
     */
    public boolean isReference() {
        return this.isReference;
    }

    /**
     * @return Whether this field has a {@link io.neba.api.annotations.Reference} annotation with a  non-empty
     * {@link io.neba.api.annotations.Reference#append() append path}.
     */
    public boolean isAppendPathPresentOnReference() {
        return isAppendPathPresentOnReference;
    }

    /**
     * @return the {@link io.neba.api.annotations.Reference#append() append path} of the
     * {@link io.neba.api.annotations.Reference} annotation, or <code>null</code> if either
     * the annotation or the value for the append path are not present.
     */
    public String getAppendPathOnReference() {
        return appendPathOnReference;
    }

    /**
     * @return Whether this field is annotated with {@link io.neba.api.annotations.This}.
     */
    public boolean isThisReference() {
        return this.isThisReference;
    }

    /**
     * @return The path from which this field's value shall be mapped; may stem
     * from the field name or a {@link io.neba.api.annotations.Path} annotation.
     */
    public ResourcePaths.ResourcePath getPath() {
        return this.path;
    }

    /**
     * @return the type the resolved value for the field shall have, which is either the {@link java.lang.reflect.Field#getType() field type},
     * or the generic parameter type in case of {@link #isLazy() lazy}} fields.
     */
    public Class<?> getType() {
        return this.fieldType;
    }

    /**
     * @return Whether this field has a {@link io.neba.api.annotations.Path} annotation.
     */
    public boolean isPathAnnotationPresent() {
        return this.isPathAnnotationPresent;
    }


    /**
     * Whether the type of this field can only be represented by a resource property (and not a resource).
     */
    public boolean isPropertyType() {
        return this.isPropertyType;
    }

    /**
     * Whether the type of this field is assignable from {@link java.util.Collection}.
     */
    public boolean isCollectionType() {
        return this.isCollectionType;
    }

    /**
     * @return whether this field {@link #isCollectionType() is a collection type}
     * that can be {@link io.neba.core.util.ReflectionUtil#instantiateCollectionType(Class) instantiated}.
     */
    public boolean isInstantiableCollectionType() {
        return isInstantiableCollectionType;
    }

    /**
     * Whether the field has a {@link io.neba.api.annotations.Children} annotation.
     */
    public boolean isChildrenAnnotationPresent() {
        return this.isChildrenAnnotationPresent;
    }

    /**
     * @return whether a {@link io.neba.api.annotations.Children} annotation is present with a non-empty
     * {@link io.neba.api.annotations.Children#resolveBelowEveryChild()} path.
     */
    public boolean isResolveBelowEveryChildPathPresentOnChildren() {
        return isResolveBelowEveryChildPathPresentOnChildren;
    }

    /**
     * @return the {@link io.neba.api.annotations.Children#resolveBelowEveryChild()} path, or
     * <code>null</code> if no such annotation or path exist.
     */
    public String getResolveBelowEveryChildPathOnChildren() {
        return resolveBelowEveryChildPathOnChildren;
    }

    /**
     * @return The generic type of this field if it has a generic type declaration, such as <code>List&lt;MyModel&gt; field;</code>
     * or <code>Optional&lt;MyModel&gt; field;</code>
     */
    public Class<?> getTypeParameter() {
        return this.typeParameter;
    }

    /**
     * @return the array type representation of the {@link #getTypeParameter() component type},
     * or <code>null</code> if the component type is <code>null</code>.
     */
    public Class<?> getArrayTypeOfTypeParameter() {
        return arrayTypeOfComponentType;
    }

    /**
     * @return the annotations of the field, never <code>null</code>.
     */
    public Annotations getAnnotations() {
        return annotations;
    }

    /**
     * @return whether the field is of type {@link io.neba.api.resourcemodels.Lazy}.
     */
    public boolean isLazy() {
        return isLazy;
    }

    @Override
    public int hashCode() {
        return this.field.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this ||
                (
                        obj != null && obj.getClass() == getClass() &&
                                ((MappedFieldMetaData) obj).field.equals(this.field)
                );
    }

    @Override
    public String toString() {
        return getClass().getName() + " [" + this.field + "]";
    }

    public interface NebaDelegatingLazyLoadingProxy {
        void setHandler(final InvocationHandler handler);
    }

    /**
     * A simple lazy loader that will obtain a value from a provided {@link Callable} at most once,
     * and retain the {@link Callable#call() obtained value} in an instance field.
     */
    private static class NebaLazyLoadingHandler implements InvocationHandler {
        static final Object UNSET = new Object();
        private final Callable<?> factory;
        private Object target = UNSET;

        private NebaLazyLoadingHandler(final Callable<?> factory) {
            this.factory = factory;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if (target == UNSET) {
                target = factory.call();
            }
            return method.invoke(target, args);
        }
    }

}
