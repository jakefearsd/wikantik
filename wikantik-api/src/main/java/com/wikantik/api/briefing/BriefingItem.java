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

/** One pinned page or cluster member surfaced in a context briefing. {@code origin} is
 *  {@code "pin"} or {@code "cluster"}; {@code included == false} means a budget-trimmed
 *  pointer (title/summary only, {@code content} is null). */
public record BriefingItem( String slug, String canonicalId, String title, String summary,
                            String origin, boolean included, String content ) {
    public BriefingItem {
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "BriefingItem.slug is required" );
        }
    }
}
