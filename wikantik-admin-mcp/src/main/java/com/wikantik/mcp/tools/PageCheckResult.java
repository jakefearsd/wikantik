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

import java.util.Map;

/**
 * Result of a single page validation check.
 *
 * <p>This is the common currency of the Strategy pattern used by the page
 * validation system.  Every {@link PageCheck} implementation returns a list
 * of these, and the audit/verify tools aggregate them into their responses.
 *
 * @param pageName  the page that was checked
 * @param severity  how serious the issue is
 * @param category  grouping key: "seo", "metadata", "structural", "staleness"
 * @param issue     machine-readable identifier, e.g. "missing_summary", "summary_too_short"
 * @param detail    human-readable description for MCP clients
 * @param autoFix   if non-null, a suggested automatic fix (action + parameters)
 */
public record PageCheckResult(
        String pageName,
        Severity severity,
        String category,
        String issue,
        String detail,
        Map< String, Object > autoFix
) {
    /** How serious the issue is. Used by audit tools to count and sort findings. */
    public enum Severity {
        /** Broken links, missing backlinks — structural problems that break navigation. */
        CRITICAL,
        /** Incomplete metadata, SEO gaps — problems that degrade quality. */
        WARNING,
        /** Stale content, style suggestions — things that could be improved. */
        SUGGESTION
    }

    /** Convenience constructor for issues without an auto-fix. */
    public PageCheckResult( final String pageName, final Severity severity,
                            final String category, final String issue, final String detail ) {
        this( pageName, severity, category, issue, detail, null );
    }
}
