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
 * X-Frame-Options: Prevents clickjacking attacks by controlling whether a page
 * can be rendered within an &lt;frame&gt;, &lt;iframe&gt;, &lt;embed&gt;, or &lt;object&gt;.
 */
public class ClickJackFilter extends SingleValueHeaderFilter {

    public ClickJackFilter() {
        super( "X-FRAME-OPTIONS", "mode", "DENY" );
    }

    /** X-Frame-Options has a closed value set; anything else keeps the DENY default. */
    @Override
    protected boolean isAcceptable( final String configured ) {
        return "DENY".equals( configured ) || "SAMEORIGIN".equals( configured );
    }
}
