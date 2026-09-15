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
package com.wikantik.extractcli.configref;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Heading anchors. Split out of {@link GenerateConfigReferenceCli} (2026-09, complexity
 * burn-down).
 */
final class AnchorSlugger {

    private static final Pattern SLUG_STRIP = Pattern.compile( "[^a-z0-9 _-]" );

    private AnchorSlugger() {}

    /** Replicates GitHub's Markdown heading-anchor slug closely enough for the table of
     *  contents links generated alongside these headings to resolve: lowercase, drop anything
     *  that isn't a letter/digit/space/hyphen/underscore, then turn spaces into hyphens (without
     *  collapsing runs — {@code "A & B"} deliberately slugs to {@code "a--b"}). */
    static String slug( final String heading ) {
        final String lower = heading.toLowerCase( Locale.ROOT );
        return SLUG_STRIP.matcher( lower ).replaceAll( "" ).replace( ' ', '-' );
    }
}
