/*
  Copyright 2013 the original author or authors.
  <p/>
  Licensed under the Apache License, Version 2.0 the "License";
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  <p/>
  http://www.apache.org/licenses/LICENSE-2.0
  <p/>
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package io.neba.spring.web;


import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

/**
 * @author Olaf Otto
 */
@RunWith(MockitoJUnitRunner.class)
public class BackgroundServletRequestWrapperTest {
    @Mock
    private HttpServletRequest request;

    private HttpSession session;

    private BackgroundServletRequestWrapper testee;

    @Before
    public void setUp() {
        testee = new BackgroundServletRequestWrapper(request);

        doThrow(new UnsupportedOperationException("THIS IS AN EXPECTED TEST EXCEPTION"))
                .when(request)
                .getSession();
    }

    @Test
    public void testRetrievalOfExistingSessionYieldsNull() {
        getExistingSession();
        assertRetrievedSessionIsNull();
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testPotentialCreationOfSessionYieldsException() {
        testee.getSession();
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testExplicitCreationOfSessionYieldsException() {
        testee.getSession(true);
    }

    private void assertRetrievedSessionIsNull() {
        assertThat(session).isNull();
    }

    private void getExistingSession() {
        session = testee.getSession(false);
    }
}