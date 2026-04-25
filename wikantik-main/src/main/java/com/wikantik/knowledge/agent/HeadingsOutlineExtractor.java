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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.HeadingOutline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks a markdown body line-by-line and collects {@code ##} / {@code ###} /
 * deeper headings into a flat outline. {@code #} is skipped because it is
 * always the page title (already in
 * {@link com.wikantik.api.agent.ForAgentProjection#title()}).
 *
 * <p>Treats fenced code blocks ({@code ``` ... ```}) as opaque so a snippet
 * containing literal {@code ## foo} is not mistaken for a heading. Caps the
 * total at {@link #MAX_ENTRIES} so a pathological page cannot blow the
 * 4 KB projection budget with headings alone.</p>
 */
public final class HeadingsOutlineExtractor {

    public static final int MAX_ENTRIES = 32;

    private static final Pattern HEADING = Pattern.compile( "^(#{2,6})\\s+(.+?)\\s*$" );
    private static final Pattern ANCHOR_SUFFIX = Pattern.compile( "\\s*\\[#[A-Za-z0-9_-]+]\\s*$" );
    private static final Pattern FENCE = Pattern.compile( "^```" );

    public List< HeadingOutline > extract( final String body ) {
        if ( body == null || body.isEmpty() ) {
            return List.of();
        }
        final List< HeadingOutline > out = new ArrayList<>();
        boolean inFence = false;
        for ( final String raw : body.split( "\\r?\\n" ) ) {
            if ( FENCE.matcher( raw ).find() ) {
                inFence = !inFence;
                continue;
            }
            if ( inFence ) continue;
            final Matcher m = HEADING.matcher( raw );
            if ( !m.matches() ) continue;
            final int level = m.group( 1 ).length();
            String text = m.group( 2 ).trim();
            text = ANCHOR_SUFFIX.matcher( text ).replaceFirst( "" ).trim();
            if ( text.isEmpty() ) continue;
            out.add( new HeadingOutline( level, text ) );
            if ( out.size() >= MAX_ENTRIES ) break;
        }
        return List.copyOf( out );
    }
}
