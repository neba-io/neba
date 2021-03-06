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

/**
 * Contains the integration of Spring MVC into Sling.<br />
 * Most importantly, the {@link io.neba.spring.mvc.MvcServlet} dispatches requests to {@link io.neba.spring.mvc.BundleSpecificDispatcherServlet bundle-specific}
 * {@code org.springframework.web.servlet.DispatcherServlet dispatcher servlets}.
 */
package io.neba.spring.mvc;
