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

import org.apache.felix.webconsole.AbstractWebConsolePlugin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import static io.neba.core.util.JsonUtil.toJson;
import static org.apache.commons.collections.CollectionUtils.find;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.startsWith;
import static org.apache.commons.lang3.StringUtils.substringAfter;
import static org.osgi.framework.Constants.SERVICE_VENDOR;

/**
 * Provides a RESTFul JSON API for {@link io.neba.api.annotations.ResourceModel} metadata,
 * i.e. the metadata collected at both registration and runtime. The metadata - in particular the
 * {@link ResourceModelStatistics} - is visualized by this console plugin on the client-side using D3.js.
 *
 * @author Olaf Otto
 */
@Component(
        service = Servlet.class,
        property = {
                "felix.webconsole.label=" + ModelStatisticsConsolePlugin.LABEL,
                "service.description=Provides a Felix console plugin visualizing resource @ResourceModel statistics.",
                SERVICE_VENDOR + "=neba.io"
        }
)
public class ModelStatisticsConsolePlugin extends AbstractWebConsolePlugin {
    static final String LABEL = "modelstatistics";
    private static final long serialVersionUID = -8676958166611686979L;
    private static final String STATISTICS_API_PATH = "/api/statistics";
    private static final String RESET_API_PATH = "/api/reset";

    @Reference
    private ResourceModelMetaDataRegistrar modelMetaDataRegistrar;

    @SuppressWarnings("unused")
    public String getCategory() {
        return "NEBA";
    }

    @Override
    public String getLabel() {
        return LABEL;
    }

    @Override
    public String getTitle() {
        return "Model statistics";
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        String suffix = substringAfter(req.getRequestURI(), req.getServletPath() + "/" + getLabel());
        if (!isBlank(suffix) && suffix.startsWith(STATISTICS_API_PATH)) {
            setNoCacheHeaders(res);
            getModelMetadata(suffix.substring(STATISTICS_API_PATH.length()), res);
            return;
        }
        if (!isBlank(suffix) && suffix.startsWith(RESET_API_PATH)) {
            setNoCacheHeaders(res);
            resetStatistics(res);
            return;
        }
        super.doGet(req, res);
    }

    private void setNoCacheHeaders(HttpServletResponse res) {
        res.setHeader("Expires", "Sat, 6 May 1970 12:00:00 GMT");
        res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        res.addHeader("Cache-Control", "post-check=0, pre-check=0");
        res.setHeader("Pragma", "no-cache");
    }

    private void resetStatistics(HttpServletResponse res) throws IOException {
        for (ResourceModelMetaData metaData : this.modelMetaDataRegistrar.get()) {
            metaData.getStatistics().reset();
        }
        prepareJsonResponse(res);
        res.getWriter().write("{\"success\": true}");
    }

    private void getModelMetadata(String typePath, HttpServletResponse res) throws IOException {
        if (typePath.isEmpty()) {
            provideStatisticsOfAllModels(res);
        } else {
            String typeName = typePath.substring(1);
            provideStatisticsOfModel(typeName, res);
        }
    }

    private void provideStatisticsOfModel(final String typeName, HttpServletResponse res) throws IOException {
        ResourceModelMetaData metaData = (ResourceModelMetaData) find(
                this.modelMetaDataRegistrar.get(), object -> ((ResourceModelMetaData) object).getTypeName().equals(typeName)
        );

        if (metaData != null) {

            Map<String, Object> data = toMap(metaData);

            ResourceModelStatistics statistics = metaData.getStatistics();
            int[] mappingDurationFrequencies = statistics.getMappingDurationFrequencies();
            int[] intervalBoundaries = statistics.getMappingDurationIntervalBoundaries();

            Map<String, Object> durationFrequencies = new LinkedHashMap<>();

            int leftBoundary = 0;
            for (int i = 0; i < mappingDurationFrequencies.length; ++i) {
                durationFrequencies.put("[" + leftBoundary + ", " + intervalBoundaries[i] + ")", mappingDurationFrequencies[i]);
                leftBoundary = intervalBoundaries[i];
            }

            data.put("mappingDurationFrequencies", durationFrequencies);

            prepareJsonResponse(res);
            res.getWriter().write(toJson(data));
        }
    }

    private void prepareJsonResponse(HttpServletResponse res) {
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json; charset=UTF-8");
    }

    private void provideStatisticsOfAllModels(HttpServletResponse res) throws IOException {
        Collection<Object> data = new LinkedList<>();
        for (ResourceModelMetaData metaData : this.modelMetaDataRegistrar.get()) {
            data.add(toMap(metaData));
        }
        prepareJsonResponse(res);
        res.getWriter().write(toJson(data));
    }

    private Map<String, Object> toMap(ResourceModelMetaData metaData) {
        ResourceModelStatistics statistics = metaData.getStatistics();
        Map<String, Object> data = new LinkedHashMap<>();

        int lazyFields = 0, greedyFields = 0;
        for (MappedFieldMetaData field : metaData.getMappableFields()) {
            boolean isLazyLoadedField = field.isLazy() ||
                    field.isChildrenAnnotationPresent() ||
                    field.isReference() && field.isInstantiableCollectionType();
            if (isLazyLoadedField) {
                ++lazyFields;
            } else {
                ++greedyFields;
            }
        }

        data.put("type", metaData.getTypeName());
        data.put("since", statistics.getSince());
        data.put("mappableFields", metaData.getMappableFields().length);
        data.put("lazyFields", lazyFields);
        data.put("greedyFields", greedyFields);
        data.put("instantiations", statistics.getInstantiations());
        data.put("mappings", statistics.getNumberOfMappings());
        data.put("averageMappingDuration", statistics.getAverageMappingDuration());
        data.put("totalMappingDuration", statistics.getTotalMappingDuration());
        data.put("maximumMappingDuration", statistics.getMaximumMappingDuration());
        data.put("minimumMappingDuration", statistics.getMinimumMappingDuration());
        data.put("mappingDurationMedian", statistics.getMappingDurationMedian());
        data.put("cacheHits", statistics.getCacheHits());
        return data;
    }

    @Override
    protected void renderContent(HttpServletRequest req, HttpServletResponse res) throws IOException {
        writeHeadnavigation(res);
        writeBody(res);
    }

    public URL getResource(String path) {
        URL url = null;
        String internalPath = substringAfter(path, "/" + getLabel());
        if (startsWith(internalPath, "/static/")) {
            url = getClass().getResource("/META-INF/consoleplugin/modelstatistics" + internalPath);
        }
        return url;
    }

    private void writeHeadnavigation(HttpServletResponse response) throws IOException {
        int numberOfModelsWithInstantiations = 0;
        double highestAverageMappingDuration = 0D;
        int highestNumberOfFields = 0;
        String nameOfModelWithHighestAverageMappingDuration = "";
        String nameOfModelWithGreatestNumberOfFields = "";
        for (ResourceModelMetaData metaData : this.modelMetaDataRegistrar.get()) {
            ResourceModelStatistics statistics = metaData.getStatistics();
            if (statistics.getInstantiations() != 0) {
                ++numberOfModelsWithInstantiations;
                double averageMappingDuration = statistics.getAverageMappingDuration();
                if (averageMappingDuration > highestAverageMappingDuration) {
                    highestAverageMappingDuration = averageMappingDuration;
                    nameOfModelWithHighestAverageMappingDuration = metaData.getTypeName();
                }
                int numberOfMappableFields = metaData.getMappableFields().length;
                if (numberOfMappableFields > highestNumberOfFields) {
                    highestNumberOfFields = numberOfMappableFields;
                    nameOfModelWithGreatestNumberOfFields = metaData.getTypeName();
                }
            }
        }

        String template = readTemplateFile("/META-INF/consoleplugin/modelstatistics/templates/head.html");
        response.getWriter().printf(template,
                numberOfModelsWithInstantiations,
                highestAverageMappingDuration,
                nameOfModelWithHighestAverageMappingDuration,
                highestNumberOfFields,
                nameOfModelWithGreatestNumberOfFields);
    }

    private void writeBody(HttpServletResponse response) throws IOException {
        String template = readTemplateFile("/META-INF/consoleplugin/modelstatistics/templates/plots.html");
        response.getWriter().print(template);
    }
}
