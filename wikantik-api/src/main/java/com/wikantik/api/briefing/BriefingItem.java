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
package com.wikantik.api.briefing;

/**
 * One page surfaced in a context briefing outside the retrieved sections.
 *
 * <p>{@code origin} says why it is there: {@code "pin"} (requested by name), {@code "cluster"} (a
 * member of a requested cluster), {@code "cited"} (a page outside the requested clusters that at
 * least one member links to) or {@code "elsewhere"} (an out-of-scope hit for the prompt, always a
 * pointer). {@code included == false} means a budget-trimmed pointer (title/summary only,
 * {@code content} is null). {@code cluster} is the page's primary cluster where the assembler sets
 * it ({@code cited} and {@code elsewhere} items, so a reader can tell whose page it is); otherwise
 * null.</p>
 */
public record BriefingItem( String slug, String canonicalId, String title, String summary,
                            String origin, boolean included, String content, String cluster ) {
    public BriefingItem {
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "BriefingItem.slug is required" );
        }
    }

    /** An item that names no cluster ({@code cluster == null}). */
    public BriefingItem( final String slug, final String canonicalId, final String title, final String summary,
                         final String origin, final boolean included, final String content ) {
        this( slug, canonicalId, title, summary, origin, included, content, null );
    }
}
