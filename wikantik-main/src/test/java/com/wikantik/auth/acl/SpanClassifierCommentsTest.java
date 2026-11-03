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
package com.wikantik.auth.acl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An access rule inside a comment the render hides locks the page ({@code IN_COMMENT}); inside a {@code %%} the render keeps
 * literal it is not in a comment. Which is which comes from the shared fixture {@code comments.json}, whose rows the rendered
 * page passes: a row whose output differs from its input hides a comment, and where it hides it is where the two texts
 * first differ.
 */
class SpanClassifierCommentsTest {

    private static final Path FIXTURE = Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "comments.json" );
    private static final String RULE = "[{ALLOW view Admin}]";

    private static boolean lockedInComment( final String text ) {
        return AccessRules.read( text ) instanceof AccessRules.Locked locked
                && locked.reasons().stream().anyMatch( r -> r.shape() == AccessRules.Shape.IN_COMMENT );
    }

    private static List< String > check( final boolean crlf ) throws Exception {
        final List< String > wrong = new ArrayList<>();
        int hidden = 0;
        for ( final JsonElement element : JsonParser.parseString( Files.readString( FIXTURE ) ).getAsJsonArray() ) {
            final JsonObject row = element.getAsJsonObject();
            final String input = row.get( "input" ).getAsString();
            final String output = row.get( "output" ).getAsString();
            final int firstMarker = input.indexOf( "%%" );
            if ( firstMarker < 0 ) {
                continue;
            }
            final boolean hides = !input.equals( output );
            int at = firstMarker;
            if ( hides ) {
                hidden++;
                int common = 0;
                while ( common < output.length() && input.charAt( common ) == output.charAt( common ) ) {
                    common++;
                }
                at = common;
                assertTrue( input.startsWith( "%%", at ), row.get( "name" ).getAsString() );
            }
            String text = input.substring( 0, at + 2 ) + RULE + input.substring( at + 2 );
            if ( crlf ) {
                text = text.replace( "\n", "\r\n" );
            }
            if ( hides && !lockedInComment( text ) ) {
                wrong.add( row.get( "name" ).getAsString() + ( crlf ? " (CRLF)" : "" ) );
            } else if ( !hides ) {
                // Observed: a literal %% leaves the page readable (Rules), except where the %% sits in inline math the
                // reader's table keeps whole: there the rule is in math and the page locks as IN_MATH, not IN_COMMENT.
                final AccessRules.Reading result = AccessRules.read( text );
                final String name = row.get( "name" ).getAsString();
                final String got = result instanceof AccessRules.Locked l
                        ? "Locked" + l.reasons().stream().map( r -> r.shape().name() ).toList() : "Rules";
                final String want = name.startsWith( "the reader's table does not split inline math" ) ? "Locked[IN_MATH]" : "Rules";
                if ( !want.equals( got ) ) {
                    wrong.add( name + ": expected " + want + " but was " + got + ( crlf ? " (CRLF)" : "" ) );
                }
            }
        }
        assertTrue( hidden > 15, "the fixture's hidden-comment rows were not found" );
        return wrong;
    }

    @Test
    void aRuleInsideACommentTheRenderHidesLocksThePage() throws Exception {
        assertEquals( List.of(), check( false ) );
    }

    @Test
    void theSameWithCrlfLineEnds() throws Exception {
        assertEquals( List.of(), check( true ) );
    }
}
