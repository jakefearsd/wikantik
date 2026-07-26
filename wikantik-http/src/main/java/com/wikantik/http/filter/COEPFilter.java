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
 * Cross-Origin-Embedder-Policy (COEP): Prevents a document from loading any
 * cross-origin resources that do not explicitly grant permission.
 */
public class COEPFilter extends SingleValueHeaderFilter {

    public COEPFilter() {
        super( "Cross-Origin-Embedder-Policy", "COEPValue", "require-corp" );
    }
}
