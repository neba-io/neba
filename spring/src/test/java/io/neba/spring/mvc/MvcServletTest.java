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
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.ServletResolver;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @author Olaf Otto
 */
@RunWith(MockitoJUnitRunner.class)
public class MvcServletTest {
    @Mock
    private ConfigurableListableBeanFactory factory;
    @Mock
    private BundleContext context;
    @Mock
    private Bundle bundle;
    @Mock
    private BundleSpecificDispatcherServlet dispatcherServlet;
    @Mock
    private SlingHttpServletRequest request;
    @Mock
    private SlingHttpServletResponse response;
    @Mock
    @SuppressWarnings("unused") // injected into testee
    private ServletConfig servletConfig;
    @Mock
    @SuppressWarnings("unused") // injected into testee
    private ServletResolver servletResolver;

    private BundleSpecificDispatcherServlet injectedDispatcherServlet;

    @InjectMocks
    @Spy
    private MvcServlet testee;

    @Before
    public void setUp() {
        doAnswer(invocation -> {
            injectedDispatcherServlet = (BundleSpecificDispatcherServlet) invocation.getArguments()[1];
            return null;
        }).when(this.factory).registerSingleton(anyString(), isA(BundleSpecificDispatcherServlet.class));

        doReturn(this.bundle)
                .when(this.context)
                .getBundle();

        doReturn("symbolic-name")
                .when(this.bundle)
                .getSymbolicName();

        doReturn(this.dispatcherServlet)
                .when(this.testee)
                .createBundleSpecificDispatcherServlet(eq(this.factory), isA(ServletConfig.class));

        doReturn("GET").when(request).getMethod();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEnableMvcRequiresNonNullFactory() {
        this.testee.enableMvc(null, this.context);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEnableMvcRequiresNonNullContext() {
        this.testee.enableMvc(this.factory, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDisableMvcRequiresNonNullBundle() {
        this.testee.disableMvc(null);
    }

    @Test
    public void testEnableMvc() {
        enableMvc();
        verifyMvcContextIsInjectedIntoFactory();
        assertInjectMvcContextIsNotNull();
    }

    @Test
    public void testOnlyResponsibleContextsServiceRequests() throws Exception {
        enableMvc();

        serviceRequest();
        verifyMvcContextDoesNotServiceRequest();

        withMvcContextResponsible();

        serviceRequest();
        verifyMvcContextServicedRequestOnce();
    }

    @Test
    public void testRemovalOfMvcCapabilities() throws Exception {
        enableMvc();
        withMvcContextResponsible();
        serviceRequest();
        verifyMvcContextServicedRequestOnce();

        disableMvc();

        serviceRequest();
        verifyMvcContextServicedRequestOnce();
    }

    @Test
    public void testServletConfigurationProvidedToBundleSpecificDispatcherServletHasSensibleServletName() {
        withRealDispatcherServletCreated();
        enableMvc();

        assertServletNameOfBundleSpecificDispatcherServletIs("BundleSpecificDispatcherServlet for bundle symbolic-name");
    }

    @Test
    public void testPatchMethodIsSupported() throws Exception {
        enableMvc();
        withMvcContextResponsible();
        withPatchRequest();

        serviceRequest();

        verifyMvcContextServicedRequestOnce();
    }

    private void withPatchRequest() {
        doReturn("PATCH").when(this.request).getMethod();
    }

    private void withRealDispatcherServletCreated() {
        doCallRealMethod().when(this.testee).createBundleSpecificDispatcherServlet(eq(this.factory), isA(ServletConfig.class));
    }

    private void assertServletNameOfBundleSpecificDispatcherServletIs(String servletName) {
        assertThat(this.injectedDispatcherServlet.getServletConfig().getServletName()).isEqualTo(servletName);
    }

    private void disableMvc() {
        this.testee.disableMvc(this.bundle);
    }

    private void verifyMvcContextServicedRequestOnce() throws ServletException, IOException {
        verify(this.dispatcherServlet).service(isA(SlingMvcServletRequest.class), isA(SlingHttpServletResponse.class));
    }

    private void withMvcContextResponsible() {
        doReturn(true).when(this.dispatcherServlet).hasHandlerFor(isA(SlingMvcServletRequest.class));
    }

    private void verifyMvcContextDoesNotServiceRequest() throws ServletException, IOException {
        verify(this.dispatcherServlet, never()).service(isA(SlingMvcServletRequest.class), isA(SlingHttpServletResponse.class));
    }

    private void serviceRequest() throws Exception {
        this.testee.service(this.request, this.response);
    }

    private void assertInjectMvcContextIsNotNull() {
        assertThat(this.injectedDispatcherServlet).isNotNull();
    }

    private void enableMvc() {
        this.testee.enableMvc(this.factory, this.context);
    }

    private void verifyMvcContextIsInjectedIntoFactory() {
        verify(this.factory).registerSingleton(anyString(), isA(BundleSpecificDispatcherServlet.class));
    }
}
