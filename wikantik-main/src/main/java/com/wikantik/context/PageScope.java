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

import com.wikantik.WikiPage;

/**
 * Holds the page-scoped state of a {@link com.wikantik.WikiContext}: the currently displayed
 * page and the "real" page (the page whose markup is currently being rendered, which may differ
 * from the displayed page when content is included via {@code InsertPage}).
 *
 * <p>Instances are created by {@code WikiContext} constructors and are not intended to be
 * constructed directly by callers outside the {@code com.wikantik} package.</p>
 *
 * @since 2.12
 */
public final class PageScope {

    private WikiPage page;
    private WikiPage realPage;

    /**
     * Constructs a new PageScope with the given page and real page.
     *
     * @param page     the currently displayed page; may be {@code null} temporarily during construction
     * @param realPage the real page being rendered; typically the same as {@code page} at creation time
     */
    public PageScope( final WikiPage page, final WikiPage realPage ) {
        this.page     = page;
        this.realPage = realPage;
    }

    /**
     * Returns the page that is being handled.
     *
     * @return the current page; may be {@code null} in edge cases during construction
     */
    public WikiPage getPage() {
        return page;
    }

    /**
     * Sets the page that is being handled.
     *
     * @param page the wiki page
     */
    public void setPage( final WikiPage page ) {
        this.page = page;
    }

    /**
     * Returns the real page whose content is currently being rendered.  This differs from
     * {@link #getPage()} when a plugin is evaluated inside an included page.
     *
     * @return the real page; may be {@code null}
     */
    public WikiPage getRealPage() {
        return realPage;
    }

    /**
     * Sets the real page whose content is currently being rendered.
     *
     * @param realPage the real page
     * @return the previous real page
     */
    public WikiPage setRealPage( final WikiPage realPage ) {
        final WikiPage old = this.realPage;
        this.realPage = realPage;
        return old;
    }

}
