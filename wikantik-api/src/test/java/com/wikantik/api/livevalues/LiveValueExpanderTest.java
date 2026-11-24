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
package com.wikantik.api.livevalues;

import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveValueExpanderTest {

    private static Function< String, Resolution > resolver( final Map< String, Resolution > m ) {
        return name -> m.getOrDefault( name, new Resolution.Unknown() );
    }

    private static final Function< String, Resolution > R = resolver( Map.of(
            "articles", new Resolution.Value( 1142 ),
            "version", new Resolution.Text( "2.8.4" ),
            "tools.admin", new Resolution.Unwired() ) );

    @Test
    void roundsDownAndMarksPlus() {
        assertEquals( "1,100+", LiveValueExpander.expandText( "{{wiki:articles|round=100}}", R ) );
        assertEquals( "1,100", LiveValueExpander.expandText( "{{wiki:articles|round=100}}",
                resolver( Map.of( "articles", new Resolution.Value( 1100 ) ) ) ) );
        assertEquals( "1,142", LiveValueExpander.expandText( "{{wiki:articles}}", R ) );
        assertEquals( "over 1,100+ pages", LiveValueExpander.expandText( "over {{wiki:articles|round=100}} pages", R ) );
    }

    @Test
    void textValueVerbatim() {
        assertEquals( "2.8.4", LiveValueExpander.expandText( "{{wiki:version}}", R ) );
        assertEquals( "2.8.4", LiveValueExpander.expandText( "{{wiki:version|round=10}}", R ) );
    }

    @Test
    void unknownStaysVerbatim() {
        assertEquals( "{{wiki:nope}}", LiveValueExpander.expandText( "{{wiki:nope}}", R ) );
    }

    @Test
    void unwiredUsesElseOrEmpty() {
        assertEquals( "several", LiveValueExpander.expandText( "{{wiki:tools.admin|else=several}}", R ) );
        assertEquals( "", LiveValueExpander.expandText( "{{wiki:tools.admin}}", R ) );
    }

    @Test
    void malformedStaysVerbatim() {
        for( final String s : new String[] { "{{wiki:}}", "{{wiki:articles|round=0}}", "{{wiki:articles|round=x}}",
                "{{wiki:articles", "{{wiki:articles|bogus=1}}", "{{wiki:Articles}}", "{{wiki:articles|round=-5}}" } ) {
            assertEquals( s, LiveValueExpander.expandText( s, R ), s );
            assertEquals( s, LiveValueExpander.expandBody( s, R ), s );
        }
    }

    @Test
    void bodySkipsCode() {
        final String t = "{{wiki:articles}}";
        final String md = String.join( "\n",
                "```",
                t,
                "```",
                "~~~",
                t,
                "~~~",
                "- item",
                "  ```",
                "  " + t,
                "  ```",
                "> ```",
                "> " + t,
                "> ```",
                "",
                "    " + t,
                "",
                "`" + t + "`",
                "``x " + t + " x``",
                "",
                "para " + t );
        final String expected = md.substring( 0, md.lastIndexOf( t ) ) + "1,142";
        assertEquals( expected, LiveValueExpander.expandBody( md, R ) );
    }

    @Test
    void longerFenceNeedsLongerClose() {
        final String md = "````\n```\n{{wiki:articles}}\n```\n````\n{{wiki:articles}}";
        assertEquals( "````\n```\n{{wiki:articles}}\n```\n````\n1,142", LiveValueExpander.expandBody( md, R ) );
    }

    @Test
    void listContinuationIsNotIndentedCode() {
        final String md = "- item\n\n    {{wiki:articles}}\n";
        assertEquals( "- item\n\n    1,142\n", LiveValueExpander.expandBody( md, R ) );
    }

    @Test
    void unmatchedBacktickIsLiteral() {
        assertEquals( "a ` b 1,142", LiveValueExpander.expandBody( "a ` b {{wiki:articles}}", R ) );
    }

    @Test
    void argsAnyOrder() {
        assertEquals( LiveValueExpander.expandText( "{{wiki:articles|else=n/a|round=100}}", R ),
                LiveValueExpander.expandText( "{{wiki:articles|round=100|else=n/a}}", R ) );
    }

    @Test
    void tokenNamesFindsAll() {
        final String text = "x {{wiki:articles}} ```{{wiki:cluster.agentic-ai/rag.pages|round=10}}``` {{wiki:bad|zz=1}}";
        assertEquals( Set.of( "articles", "cluster.agentic-ai/rag.pages", "bad" ), LiveValueExpander.tokenNames( text ) );
        assertTrue( LiveValueExpander.hasToken( text ) );
        assertFalse( LiveValueExpander.hasToken( "no tokens {{wiki:}}" ) );
        assertTrue( LiveValueExpander.BUILT_IN_NAMES.contains( "tools.admin" ) );
    }
}
