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
package com.wikantik.mcp.tools;

import com.wikantik.api.core.Page;
import com.wikantik.pages.PageManager;

import java.util.Map;

/**
 * Context object passed to {@link PageCheck} implementations.
 *
 * <p>Carries everything a check might need about a single page, avoiding long
 * parameter lists and letting checks pull only the data they care about.
 *
 * @param pageName    the wiki page name being checked
 * @param metadata    the parsed frontmatter metadata (may be empty, never null)
 * @param body        the Markdown body text (may be empty, never null)
 * @param page        the Page object (nullable — not available for non-existent pages)
 * @param pageManager for checks that need to look up other pages (e.g. broken link checks)
 */
public record PageCheckContext(
        String pageName,
        Map< String, Object > metadata,
        String body,
        Page page,
        PageManager pageManager
) {
}
