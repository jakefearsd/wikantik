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
package com.wikantik.api.frontmatter.schema;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request-scoped hand-off for non-blocking {@code WARNING} violations from the save filter to the
 * caller (REST resource or MCP write tool). The filter runs synchronously inside
 * {@code PageManager.saveText} on the request thread, so a {@link ThreadLocal} carries warnings back.
 *
 * <p><b>Keyed by page name.</b> A single {@code saveText} can trigger <em>nested</em> saves of other
 * pages (e.g. regenerating a generated index/hub page), each running the validation filter. A flat
 * per-thread slot let the nested save's warnings clobber the outer page's — so {@code update_page}
 * could surface a warning ("summary is 188 chars") computed for a <em>different</em> page. Keying the
 * stash by page name isolates each save's warnings, so the caller drains exactly its own page.</p>
 *
 * <p>Contract: the caller {@link #clear()}s before saving (defensive) and {@link #drain(String)}s the
 * page it saved afterward (drain removes that page's entry). A pooled thread never leaks warnings
 * between requests.</p>
 */
public final class FrontmatterWarningSink {

    private static final ThreadLocal< Map< String, List< FieldViolation > > > WARNINGS =
            ThreadLocal.withInitial( HashMap::new );

    private FrontmatterWarningSink() {}

    /**
     * Stash the warnings produced while validating the save of {@code pageName}. A {@code null} page
     * name (e.g. a context-less unit-test invocation) is stored under a stable empty key.
     */
    public static void put( final String pageName, final List< FieldViolation > warnings ) {
        WARNINGS.get().put( key( pageName ), warnings );
    }

    /** Return and remove the stashed warnings for {@code pageName} (empty list if none). */
    public static List< FieldViolation > drain( final String pageName ) {
        final Map< String, List< FieldViolation > > map = WARNINGS.get();
        final List< FieldViolation > w = map.remove( key( pageName ) );
        if ( map.isEmpty() ) {
            WARNINGS.remove();
        }
        return w == null ? List.of() : w;
    }

    /** Clear all stashed warnings on this thread before initiating a save. */
    public static void clear() {
        WARNINGS.remove();
    }

    private static String key( final String pageName ) {
        return pageName == null ? "" : pageName;
    }
}
