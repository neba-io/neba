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

package io.neba.core.resourcemodels.registration;

import io.neba.core.util.ConcurrentDistinctMultiValueMap;
import io.neba.core.util.Key;
import io.neba.core.util.MatchedBundlesPredicate;
import io.neba.core.util.OsgiModelSource;
import io.neba.core.util.ResolvedModelSource;
import org.apache.commons.collections.CollectionUtils;
import org.apache.sling.api.resource.Resource;
import org.osgi.framework.Bundle;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static io.neba.core.resourcemodels.registration.MappableTypeHierarchy.mappableTypeHierarchyOf;
import static io.neba.core.util.BundleUtil.displayNameOf;
import static io.neba.core.util.NodeUtil.geMixinTypes;
import static io.neba.core.util.NodeUtil.getPrimaryType;
import static java.util.Collections.unmodifiableCollection;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Contains {@link OsgiModelSource model sources} associated to
 * {@link MappableTypeHierarchy mappable types} and the corresponding logic to
 * lookup these relationships.
 *
 * @author Olaf Otto
 */
@Component(service = ModelRegistry.class)
public class ModelRegistry {
    /**
     * Generate a {@link Key} representing both the
     * {@link org.apache.sling.api.resource.Resource#getResourceType() sling resource type},
     * {@link javax.jcr.Node#getPrimaryNodeType() primary node type} and the {@link Node#getMixinNodeTypes() mixin types}
     * of the resource, if any. Rationale: Resources may have the same <code>sling:resourceType</code>, but different primary or mixin types,
     * thus potentially producing different results when mapped. The cache must thus use these
     * types as a key for cached adaptation results.<br />
     *
     * @param resource           must not be <code>null</code>.
     * @param furtherKeyElements can be <code>null</code>
     * @return never <code>null</code>.
     */
    private static Key key(Resource resource, Object... furtherKeyElements) {
        final Key furtherElementsKey = furtherKeyElements == null ? null : new Key(furtherKeyElements);
        final Node node = resource.adaptTo(Node.class);

        if (node == null) {
            return new Key(resource.getResourceType(), furtherElementsKey);
        }

        try {
            return new Key(
                    resource.getResourceType(),
                    resource.getResourceSuperType(),
                    getPrimaryType(node),
                    geMixinTypes(node),
                    furtherElementsKey);
        } catch (RepositoryException e) {
            throw new RuntimeException("Unable to retrieve the primary type of " + resource + ".", e);
        }
    }

    /**
     * @param source can be <code>null</code>.
     * @param <T>    the collection type.
     * @return the collection, or <code>null</code> if the collection
     * id <code>null</code> or empty.
     */
    private static <T> Collection<T> nullIfEmpty(Collection<T> source) {
        return source == null || source.isEmpty() ? null : source;
    }

    /**
     * @param sources        can be <code>null</code>.
     * @param compatibleType can be <code>null</code>.
     * @return the original collection if sources or compatibleType are
     * <code>null</code>, or a collection representing the models
     * compatible to the given type.
     */
    private static Collection<OsgiModelSource<?>> filter(Collection<OsgiModelSource<?>> sources, Class<?> compatibleType) {
        Collection<OsgiModelSource<?>> compatibleSources = sources;
        if (sources != null && compatibleType != null) {
            compatibleSources = new ArrayList<>(sources.size());
            for (OsgiModelSource<?> source : sources) {
                if (compatibleType.isAssignableFrom(source.getModelType())) {
                    compatibleSources.add(source);
                }
            }
        }
        return compatibleSources;
    }

    /**
     * @param sources   can be <code>null</code>.
     * @param modelName can be <code>null</code>.
     * @return the original collection if sources or modelName are
     * <code>null</code>, or a collection representing the models
     * who's {@link OsgiModelSource#getModelName()} model name}
     * is equal to the given model name.
     */
    private static Collection<OsgiModelSource<?>> filter(Collection<OsgiModelSource<?>> sources, String modelName) {
        Collection<OsgiModelSource<?>> sourcesWithModelName = sources;
        if (sources != null && modelName != null) {
            sourcesWithModelName = new ArrayList<>(sources.size());
            for (OsgiModelSource<?> source : sources) {
                if (modelName.equals(source.getModelName())) {
                    sourcesWithModelName.add(source);
                }
            }
        }
        return sourcesWithModelName;
    }

    private final ConcurrentDistinctMultiValueMap<String, OsgiModelSource<?>> typeNameToModelSourcesMap = new ConcurrentDistinctMultiValueMap<>();
    private final ConcurrentDistinctMultiValueMap<Key, ResolvedModelSource<?>> lookupCache = new ConcurrentDistinctMultiValueMap<>();
    private final Logger logger = getLogger(getClass());

    /**
     * Finds the most specific models for the given {@link Resource}. The model's model
     * name must match the provided model name.
     *
     * @param resource  must not be <code>null</code>.
     * @param modelName must not be <code>null</code>.
     * @return the resolved models, or <code>null</code> if no such models exist.
     */
    Collection<ResolvedModelSource<?>> lookupMostSpecificModels(Resource resource, String modelName) {
        if (resource == null) {
            throw new IllegalArgumentException("Method argument resource must not be null.");
        }
        if (modelName == null) {
            throw new IllegalArgumentException("Method argument modelName must not be null.");
        }

        Key key = key(resource, modelName);

        Collection<ResolvedModelSource<?>> matchingModels = this.lookupCache.get(key);
        if (matchingModels == null) {
            matchingModels = lookupCache.computeIfAbsent(key, k -> resolveMostSpecificModelSources(resource, modelName));
        }

        return nullIfEmpty(matchingModels);
    }

    /**
     * Finds the most specific models for the given {@link Resource}, i.e. the
     * first model(s) found when traversing the resource's
     * {@link MappableTypeHierarchy mappable hierarchy}.
     *
     * @param resource must not be <code>null</code>.
     * @return the model sources, or <code>null</code> if no models exist for the resource.
     */
    Collection<ResolvedModelSource<?>> lookupMostSpecificModels(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Method argument resource must not be null.");
        }

        final Key key = key(resource);

        Collection<ResolvedModelSource<?>> matchingModels = this.lookupCache.get(key);
        if (matchingModels == null) {
            matchingModels = lookupCache.computeIfAbsent(key, k -> resolveMostSpecificModelSources(resource));
        }

        return nullIfEmpty(matchingModels);
    }

    /**
     * Finds the all models for the given {@link Resource}, i.e. the
     * all model(s) found when traversing the resource's
     * {@link MappableTypeHierarchy mappable hierarchy}.
     *
     * @param resource must not be <code>null</code>.
     * @return the model sources, or <code>null</code> if no models exist for the resource.
     */
    Collection<ResolvedModelSource<?>> lookupAllModels(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Method argument resource must not be null.");
        }

        final Key key = key(resource, "allModels");

        Collection<ResolvedModelSource<?>> matchingModels = this.lookupCache.get(key);
        if (matchingModels == null) {
            matchingModels = lookupCache.computeIfAbsent(key, k -> resolveModelSources(resource, null, false));
        }

        return nullIfEmpty(matchingModels);
    }

    /**
     * Finds the most specific models for the given resource which are
     * {@link Class#isAssignableFrom(Class) assignable to} the target type.
     *
     * @param resource   must not be <code>null</code>.
     * @param targetType must not be <code>null</code>.
     * @return the sources of the models, or <code>null</code> if no such model exists.
     */
    public Collection<ResolvedModelSource<?>> lookupMostSpecificModels(Resource resource, Class<?> targetType) {
        if (resource == null) {
            throw new IllegalArgumentException("Method argument resource must not be null.");
        }
        if (targetType == null) {
            throw new IllegalArgumentException("Method argument targetType must not be null.");
        }

        final Key key = key(resource, targetType);

        Collection<ResolvedModelSource<?>> matchingModels = this.lookupCache.get(key);
        if (matchingModels == null) {
            matchingModels = lookupCache.computeIfAbsent(key, k -> resolveMostSpecificModelSources(resource, targetType));
        }

        return nullIfEmpty(matchingModels);
    }

    /**
     * Clears the registry upon shutdown.
     */
    @Deactivate
    protected void deActivate() {
        this.logger.info("The model registry is shutting down.");
        clearRegisteredModels();
        clearLookupCaches();
    }

    /**
     * Removes all resource models originating from the given bundle from this registry.
     *
     * @param bundle must not be <code>null</code>.
     */
    void removeResourceModels(final Bundle bundle) {
        this.logger.info("Removing resource models of bundle " + displayNameOf(bundle) + "...");
        MatchedBundlesPredicate sourcesWithBundles = new MatchedBundlesPredicate(bundle);
        for (Collection<OsgiModelSource<?>> values : this.typeNameToModelSourcesMap.values()) {
            CollectionUtils.filter(values, sourcesWithBundles);
        }
        clearLookupCaches();
        this.logger.info("Removed " + sourcesWithBundles.getFilteredElements()
                + " resource models of bundle " + displayNameOf(bundle) + "...");
    }

    /**
     * @return a shallow copy of all registered sources for models, never
     * <code>null</code> but rather an empty list.
     */
    public List<OsgiModelSource<?>> getModelSources() {
        Collection<Collection<OsgiModelSource<?>>> sources = this.typeNameToModelSourcesMap.values();
        List<OsgiModelSource<?>> linearizedSources = new LinkedList<>();
        for (Collection<OsgiModelSource<?>> source : sources) {
            linearizedSources.addAll(source);
        }
        return linearizedSources;
    }

    /**
     * Adds the type[] -&t; model relationship to the registry.
     *
     * @param types  must not be <code>null</code>.
     * @param source must not be <code>null</code>.
     */
    public void add(String[] types, OsgiModelSource<?> source) {
        for (String resourceType : types) {
            this.typeNameToModelSourcesMap.put(resourceType, source);
        }
        clearLookupCaches();
    }

    /**
     * @return all type -&gt; model mappings.
     */
    Map<String, Collection<OsgiModelSource<?>>> getTypeMappings() {
        return this.typeNameToModelSourcesMap.shallowCopy();
    }

    /**
     * Clears all quick lookup caches for resource models, but
     * not the registry itself.
     */
    void clearLookupCaches() {
        this.lookupCache.clear();
        this.logger.debug("Cache cleared.");
    }

    private void clearRegisteredModels() {
        this.typeNameToModelSourcesMap.clear();
        this.logger.debug("Registry cleared.");
    }

    /**
     * @see #resolveMostSpecificModelSources(org.apache.sling.api.resource.Resource, Class)
     */
    private Collection<ResolvedModelSource<?>> resolveMostSpecificModelSources(Resource resource) {
        return resolveMostSpecificModelSources(resource, (Class<?>) null);
    }

    /**
     * @see #resolveModelSources(org.apache.sling.api.resource.Resource, Class, boolean)
     */
    private Collection<ResolvedModelSource<?>> resolveMostSpecificModelSources(
            Resource resource,
            Class<?> compatibleType) {

        return resolveModelSources(resource, compatibleType, true);
    }

    /**
     * Finds all {@link OsgiModelSource model sources} representing models for the given
     * {@link Resource}.
     *
     * @param resource            must not be <code>null</code>.
     * @param compatibleType      can be <code>null</code>. If provided, only models
     *                            compatible to the given type are returned.
     * @param resolveMostSpecific whether to resolve only the most specific models.
     * @return never <code>null</code> but rather an empty collection.
     */
    private Collection<ResolvedModelSource<?>> resolveModelSources(Resource resource, Class<?> compatibleType, boolean resolveMostSpecific) {
        Collection<ResolvedModelSource<?>> sources = new ArrayList<>(64);
        for (final String resourceType : mappableTypeHierarchyOf(resource)) {
            Collection<OsgiModelSource<?>> allSourcesForType = this.typeNameToModelSourcesMap.get(resourceType);
            Collection<OsgiModelSource<?>> sourcesForCompatibleType = filter(allSourcesForType, compatibleType);
            if (sourcesForCompatibleType != null && !sourcesForCompatibleType.isEmpty()) {
                List<ResolvedModelSource<?>> list = new ArrayList<>();
                for (OsgiModelSource<?> source : sourcesForCompatibleType) {
                    ResolvedModelSource<?> resolvedModelSource = new ResolvedModelSource<>(source, resourceType);
                    list.add(resolvedModelSource);
                }
                sources.addAll(list);
                if (resolveMostSpecific) {
                    break;
                }
            }
        }
        return unmodifiableCollection(sources);
    }

    /**
     * Finds all {@link OsgiModelSource model sources} representing models for the given
     * {@link Resource} who's {@link OsgiModelSource#getModelName() model name}
     * matches the given model name.
     *
     * @param resource  must not be <code>null</code>.
     * @param modelName can be <code>null</code>.
     * @return never <code>null</code> but rather an empty collection.
     */
    private Collection<ResolvedModelSource<?>> resolveMostSpecificModelSources(Resource resource, String modelName) {
        Collection<ResolvedModelSource<?>> sources = new ArrayList<>();
        for (final String resourceType : mappableTypeHierarchyOf(resource)) {
            Collection<OsgiModelSource<?>> allSourcesForType = this.typeNameToModelSourcesMap.get(resourceType);
            Collection<OsgiModelSource<?>> sourcesWithMatchingModelName = filter(allSourcesForType, modelName);
            if (sourcesWithMatchingModelName != null && !sourcesWithMatchingModelName.isEmpty()) {
                List<ResolvedModelSource<?>> list = new ArrayList<>();
                for (OsgiModelSource<?> source : sourcesWithMatchingModelName) {
                    ResolvedModelSource<?> resolvedModelSource = new ResolvedModelSource<>(source, resourceType);
                    list.add(resolvedModelSource);
                }
                sources.addAll(list);
                break;
            }
        }
        return unmodifiableCollection(sources);
    }
}
