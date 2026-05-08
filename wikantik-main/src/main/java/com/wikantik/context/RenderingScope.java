/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.context;

/**
 * Holds the rendering-scoped state of a {@link com.wikantik.WikiContext}: the template directory
 * name used to render the response.
 *
 * <p>This is the smallest of the three context scopes.  It is separated from
 * {@link RequestScope} and {@link PageScope} so that rendering configuration can be examined
 * and modified independently of HTTP or page state.</p>
 *
 * <p>Instances are created by {@code WikiContext} constructors and are not intended to be
 * constructed directly by callers outside the {@code com.wikantik} package.</p>
 *
 * @since 2.12
 */
public final class RenderingScope {

    private String template;

    /**
     * Constructs a new RenderingScope with the given template name.
     *
     * @param template the template directory name; must not be {@code null}
     */
    public RenderingScope( final String template ) {
        this.template = template;
    }

    /**
     * Returns the name of the template directory used to render the page.
     *
     * @return the template name; never {@code null}
     */
    public String getTemplate() {
        return template;
    }

    /**
     * Sets the name of the template directory to use for rendering.
     *
     * @param template the template directory name; must not be {@code null}
     */
    public void setTemplate( final String template ) {
        this.template = template;
    }

}
