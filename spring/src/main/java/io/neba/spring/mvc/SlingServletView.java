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
package io.neba.spring.mvc;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.springframework.web.servlet.View;

import javax.annotation.Nonnull;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a resource path to a {@link Servlet} representing a {@code org.apache.sling.api.scripting.SlingScript}
 * and invokes the script to {@link #render(Map, HttpServletRequest, HttpServletResponse) render} the view.
 *
 * @author Olaf Otto
 */
public class SlingServletView implements View {
    private final String resourceType;
    private final Servlet servlet;

    /**
     * @param resourceType must not be <code>null</code>.
     * @param servlet must not be <code>null</code>.
     */
    public SlingServletView(String resourceType, Servlet servlet) {
        if (resourceType == null) {
            throw new IllegalArgumentException("Method argument resourceType must not be null.");
        }
        if (servlet == null) {
            throw new IllegalArgumentException("Method argument servlet must not be null.");
        }
        this.resourceType = resourceType;
        this.servlet = servlet;
    }

    @Override
    public String getContentType() {
        return null;
    }

    /**
     * @param model    can be <code>null</code>.
     * @param request  must not be <code>null</code>.
     * @param response must not be <code>null</code>.
     */
    @Override
    public void render(Map<String, ?> model, HttpServletRequest request, @Nonnull HttpServletResponse response) throws Exception {
        final SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
        final ResourceResolver resourceResolver = slingRequest.getResourceResolver();
        final String resourcePath = request.getPathInfo();

        final Resource resource = new SpringControllerModelResource(resourceResolver, resourcePath, this.resourceType, model);
        final SlingHttpServletRequest wrapped = new MvcResourceRequest(slingRequest, resource);

        if (model != null) {
            for (Map.Entry<String, ?> entry : model.entrySet()) {
                wrapped.setAttribute(entry.getKey(), entry.getValue());
            }
        }

        servlet.service(wrapped, response);
    }

    /**
     * Wraps the original controller request to override the
     * {@link #getResource() request's resource}.
     *
     * @author Olaf Otto
     */
    static class MvcResourceRequest extends SlingHttpServletRequestWrapper {
        private final Resource resource;

        MvcResourceRequest(SlingHttpServletRequest slingRequest, Resource resource) {
            super(slingRequest);
            this.resource = resource;
        }

        @Override
        @Nonnull
        public Resource getResource() {
            return resource;
        }
    }

    /**
     * Represents the result of a spring controller invocation as a {@link Resource}. The underlying
     * {@link org.springframework.ui.Model model} is provided via the {@link Resource {@link #getValueMap()} value map representation}
     * of this resource.
     *
     * @author Olaf Otto
     */
    private static class SpringControllerModelResource extends SyntheticResource {
        private final Map<String, ?> model;

        SpringControllerModelResource(ResourceResolver resourceResolver, String resourcePath, String resourceType, Map<String, ?> model) {
            super(resourceResolver, resourcePath, resourceType);
            this.model = model;
        }

        @Override
        @Nonnull
        public ValueMap getValueMap() {
            ValueMap properties = new ValueMapDecorator(new HashMap<>());
            if (this.model != null) {
                properties.putAll(this.model);
            }
            return properties;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (type.isAssignableFrom(ValueMap.class)) {
                return (AdapterType) getValueMap();
            }
            return super.adaptTo(type);
        }
    }
}
