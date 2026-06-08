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

import java.util.List;

/**
 * Request-scoped hand-off for non-blocking {@code WARNING} violations from the save filter to the REST
 * layer. The filter runs synchronously inside {@code PageManager.saveText} on the request thread, so a
 * {@link ThreadLocal} reliably carries warnings back to the resource that initiated the save.
 *
 * <p>Contract: {@code PageResource} {@link #clear()}s before saving and {@link #drain()}s after (drain
 * also clears), so a pooled thread never leaks warnings between requests.</p>
 */
public final class FrontmatterWarningSink {

    private static final ThreadLocal< List< FieldViolation > > WARNINGS = new ThreadLocal<>();

    private FrontmatterWarningSink() {}

    /** Stash the warnings produced while validating the current save. */
    public static void put( final List< FieldViolation > warnings ) {
        WARNINGS.set( warnings );
    }

    /** Return and clear the stashed warnings (empty list if none). */
    public static List< FieldViolation > drain() {
        final List< FieldViolation > w = WARNINGS.get();
        WARNINGS.remove();
        return w == null ? List.of() : w;
    }

    /** Clear any stale warnings before initiating a save. */
    public static void clear() {
        WARNINGS.remove();
    }
}
