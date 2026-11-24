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

import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.livevalues.LiveValues;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveFrontmatterTest {

    private final LiveValues lv = mock( LiveValues.class );

    @Test
    void expandsOnlySummaryAndLeavesOriginalUntouched() {
        when( lv.expandText( "Over {{wiki:articles}}" ) ).thenReturn( "Over 5" );
        final ParsedPage in = new ParsedPage( Map.of( "summary", "Over {{wiki:articles}}", "title", "{{wiki:articles}}" ), "body" );
        final ParsedPage out = LiveFrontmatter.expandSummary( in, lv );
        assertEquals( "Over 5", out.metadata().get( "summary" ) );
        assertEquals( "{{wiki:articles}}", out.metadata().get( "title" ) );
        assertEquals( "body", out.body() );
        assertEquals( "Over {{wiki:articles}}", in.metadata().get( "summary" ) );
        assertNotSame( in, out );
    }

    @Test
    void nullServiceOrNoSummaryReturnsSameInstance() {
        final ParsedPage withSummary = new ParsedPage( Map.of( "summary", "x" ), "b" );
        final ParsedPage without = new ParsedPage( Map.of( "tags", "x" ), "b" );
        assertSame( withSummary, LiveFrontmatter.expandSummary( withSummary, null ) );
        assertSame( without, LiveFrontmatter.expandSummary( without, lv ) );
    }

    @Test
    void expandForReadersExpandsSummaryAndBody() {
        when( lv.expandText( "S {{wiki:articles}}" ) ).thenReturn( "S 5" );
        when( lv.expandBody( "B {{wiki:articles}}" ) ).thenReturn( "B 5" );
        final ParsedPage in = new ParsedPage( Map.of( "summary", "S {{wiki:articles}}" ), "B {{wiki:articles}}" );
        final ParsedPage out = LiveFrontmatter.expandForReaders( in, lv );
        assertEquals( "S 5", out.metadata().get( "summary" ) );
        assertEquals( "B 5", out.body() );
        assertEquals( "B {{wiki:articles}}", in.body() );
        assertSame( in, LiveFrontmatter.expandForReaders( in, null ) );
    }

    @Test
    void expandRawTextExpandsSummaryAndBodyOnly() {
        final StubLiveValues stub = new StubLiveValues();
        final String t = StubLiveValues.TOKEN;
        final String in = "---\ntitle: " + t + "\nsummary: Over " + t + " now\n---\nBody " + t + ".\n";
        final String out = LiveFrontmatter.expandRawText( in, stub );
        assertEquals( "---\ntitle: " + t + "\nsummary: Over " + StubLiveValues.VALUE + " now\n---\nBody "
                + StubLiveValues.VALUE + ".\n", out );
        assertSame( in, LiveFrontmatter.expandRawText( in, null ) );
    }
}
