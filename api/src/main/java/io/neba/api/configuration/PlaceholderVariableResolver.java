/**
 * Copyright 2013 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 the "License";
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
**/

package io.neba.api.configuration;

import javax.annotation.Nonnull;

/**
 * A source for values of variables of the form
 * <pre>${key}</pre>.
 * 
 * @author Olaf Otto
 */
public interface PlaceholderVariableResolver {
    /**
     * Invoked to resolve variables of the form ${name}.
     * Example: For ${name}, invoked with "name".
     * @param variableName never null.
     * @return the resolved value, or null if no value can be resolved for the 
     * variable name.
     */
	String resolve(@Nonnull String variableName);
}
