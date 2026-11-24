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
package com.wikantik.livevalues;

import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.livevalues.LiveValueExpander;

import java.util.ArrayList;
import java.util.List;

/**
 * Save-time advisory: an edit that drops {@code {{wiki:...}}} tokens the previous version held. Reader surfaces
 * (other than the admin {@code read_page}) show values, so an agent that edited from one of them saves text with the
 * values baked in and the tokens gone. The save still succeeds; the caller is told which tokens were lost.
 */
public final class LiveValueLoss {

    /** The {@link FieldViolation#code()} of the advisory. */
    public static final String CODE = "livevalues.lost";

    private LiveValueLoss() {
    }

    /**
     * Tokens present in {@code before} that {@code after} holds fewer of (multiset comparison on the token text).
     *
     * @return the lost tokens, one entry per missing occurrence; empty when nothing was lost
     */
    public static List< String > lostTokens( final String before, final String after ) {
        final List< String > remaining = new ArrayList<>( LiveValueExpander.tokens( after ) );
        final List< String > lost = new ArrayList<>();
        for( final String token : LiveValueExpander.tokens( before ) ) {
            if( !remaining.remove( token ) ) {
                lost.add( token );
            }
        }
        return lost;
    }

    /** The advisory as a warning, or an empty list when the edit lost no token. */
    public static List< FieldViolation > warnings( final String before, final String after ) {
        final List< String > lost = lostTokens( before, after );
        if( lost.isEmpty() ) {
            return List.of();
        }
        return List.of( FieldViolation.of( null, Severity.WARNING, CODE,
                "This save removed live-value tokens the page held: " + String.join( " ", lost )
                        + ". Reader surfaces show values in their place; edit from the admin read_page text, "
                        + "which keeps the tokens, or the tokens (and the live updates) are gone." ) );
    }
}
