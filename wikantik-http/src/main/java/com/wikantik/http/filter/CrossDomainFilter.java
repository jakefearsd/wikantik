/*
 * Copyright 2025 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.http.filter;

/**
 * X-Permitted-Cross-Domain-Policies: Restricts cross-domain data loading by
 * specific plugins, such as Flash.
 */
public class CrossDomainFilter extends SingleValueHeaderFilter {

    public CrossDomainFilter() {
        super( "X-Permitted-Cross-Domain-Policies", "XDomainValue", "none" );
    }
}
