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
package org.apache.wiki.content;

import org.apache.wiki.api.engine.Initializable;

import java.util.Set;

/**
 * Registry of system/template pages that ship with JSPWiki. System pages include
 * menu fragments (LeftMenu, MoreMenu), help pages (LoginHelp, EditPageHelp),
 * CSS theme pages, and other template-provided pages.
 *
 * <p>System pages are auto-discovered at startup from the {@code jspwiki-wikipages}
 * classpath JAR. Additional patterns can be added via the
 * {@value #PROP_EXTRA_PATTERNS} property.
 *
 * @since 3.0.7
 */
public interface SystemPageRegistry extends Initializable {

    /** Property for additional system page name patterns (comma-separated regex). */
    String PROP_EXTRA_PATTERNS = "jspwiki.systemPages.extraPatterns";

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
    Set<String> getSystemPageNames();
}
