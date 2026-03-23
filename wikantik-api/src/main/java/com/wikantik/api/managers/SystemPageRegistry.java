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
package com.wikantik.api.managers;

import com.wikantik.api.engine.Initializable;

import java.util.Set;

/**
 * Registry of system/template pages that ship with the wiki engine.
 * System pages include menu fragments, help pages, CSS theme pages,
 * and other template-provided pages.
 *
 * <p>This interface lives in wikantik-api so that modules like wikantik-mcp
 * can depend on the contract without depending on wikantik-main's
 * implementation.
 *
 * @since 3.0.7
 */
public interface SystemPageRegistry extends Initializable {

    /** Property for additional system page name patterns (comma-separated regex). */
    String PROP_EXTRA_PATTERNS = "wikantik.systemPages.extraPatterns";

    /**
     * Returns {@code true} if the given page name refers to a system/template page.
     *
     * @param pageName the wiki page name to check
     * @return true if the page is a system page
     */
    boolean isSystemPage( String pageName );

    /**
     * Returns the set of all discovered system page names.
     *
     * @return unmodifiable set of system page names
     */
    Set< String > getSystemPageNames();
}
