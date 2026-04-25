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
package com.wikantik.api.agent;

/**
 * One agent-consumable fact extracted from a page. Either authored verbatim in
 * frontmatter under {@code key_facts:}, or synthesised from the body's first
 * paragraphs by a heuristic. {@code text} is the only required field;
 * {@code sourceHint} is an optional pointer ({@code "frontmatter"} /
 * {@code "body"}) for debugging.
 */
public record KeyFact( String text, String sourceHint ) {
    public KeyFact {
        if ( text == null || text.isBlank() ) {
            throw new IllegalArgumentException( "KeyFact.text required" );
        }
    }

    public static KeyFact of( final String text ) {
        return new KeyFact( text, null );
    }
}
