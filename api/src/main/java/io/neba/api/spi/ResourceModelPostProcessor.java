/*
  Copyright 2013 the original author or authors.
  <p>
  Licensed under the Apache License, Version 2.0 the "License";
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  <p>
  http://www.apache.org/licenses/LICENSE-2.0
  <p>
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package io.neba.api.spi;

import org.apache.sling.api.resource.Resource;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Lifecycle callback for
 * {@link io.neba.api.annotations.ResourceModel resource models}.
 * <p>
 * {@link #processAfterMapping(Object, Resource, ResourceModelFactory)} is invoked after
 * the model was created and injected and all
 * resource properties are mapped. All lifecycle callbacks where already
 * called at this point.
 * </p>
 * OSGi services providing this interface are automatically detected by the core
 * and are applied to <em>all</em> resource models. There are no guarantees concerning the
 * order in which these post processors are invoked. If a specific order is
 * required, implementing a {@link ResourceModelPostProcessor} that delegates to
 * other post processors in the desired order is advised.
 *
 * @author Olaf Otto
 * @since 1.0.0
 */
public interface ResourceModelPostProcessor {
    /**
     * Lifecycle callback invoked after the resource properties are mapped onto
     * the {@link io.neba.api.annotations.ResourceModel}.
     *
     * @param resourceModel is never <code>null</code>.
     * @param resource      is never <code>null</code>.
     * @param factory       is never <code>null</code>.
     * @return a new resource model overriding the provided resourceModel, or
     * <code>null</code> if the resource model is not to be changed.
     */
    @CheckForNull
    <T> T processAfterMapping(@Nonnull T resourceModel, @Nonnull Resource resource, @Nonnull ResourceModelFactory factory);
}
