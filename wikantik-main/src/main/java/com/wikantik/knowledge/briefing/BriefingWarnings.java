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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.briefing.ContextBriefing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The warning both briefing surfaces ({@code get_briefing} MCP and {@code GET /api/briefing}) add
 * for a request argument they do not recognise. A misspelt or invented argument (a client that sends
 * {@code scope=strict} instead of {@code scope_mode}) is otherwise silently ignored, which reads as a
 * successful but unscoped briefing. The request still runs with defaults; the caller is only told.
 */
public final class BriefingWarnings {

    /** Warning text prefix; the offending argument name follows. */
    public static final String UNKNOWN_ARGUMENT_PREFIX = "unknown argument: ";

    /** Longest argument name echoed back. The name is caller-supplied and ends up in injection-ready markdown. */
    static final int MAX_NAME_CHARS = 64;

    private BriefingWarnings() {}

    /**
     * Returns {@code briefing} with one {@code "unknown argument: <name>"} warning appended per
     * name in {@code supplied} that is not in {@code known}, in sorted order after the existing warnings.
     * Returns {@code briefing} itself when nothing is unknown.
     *
     * @param briefing the (already gated) briefing
     * @param supplied the argument / query-parameter names the caller sent; {@code null} means none
     * @param known    the names the surface understands
     */
    public static ContextBriefing withUnknownArguments( final ContextBriefing briefing,
                                                        final Collection< String > supplied,
                                                        final Set< String > known ) {
        if ( supplied == null || supplied.isEmpty() ) return briefing;
        final Set< String > unknown = new TreeSet<>();
        for ( final String name : supplied ) {
            if ( name != null && !known.contains( name ) ) unknown.add( name );
        }
        if ( unknown.isEmpty() ) return briefing;

        final List< String > warnings = new ArrayList<>( briefing.warnings() );
        for ( final String name : unknown ) {
            warnings.add( UNKNOWN_ARGUMENT_PREFIX + echo( name ) );
        }
        return new ContextBriefing( briefing.prompt(), briefing.sections(), briefing.coverage(), briefing.items(),
            warnings, briefing.budgetTokens(), briefing.usedTokens() );
    }

    /** Caps the name and replaces control characters so it cannot break the surrounding markdown line. */
    private static String echo( final String name ) {
        final String capped = name.length() > MAX_NAME_CHARS ? name.substring( 0, MAX_NAME_CHARS ) : name;
        final StringBuilder sb = new StringBuilder( capped.length() );
        for ( int i = 0; i < capped.length(); i++ ) {
            final char c = capped.charAt( i );
            sb.append( Character.isISOControl( c ) ? '?' : c );
        }
        return sb.toString();
    }
}
