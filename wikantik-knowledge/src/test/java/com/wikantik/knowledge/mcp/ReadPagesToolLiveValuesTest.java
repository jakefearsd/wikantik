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
package com.wikantik.knowledge.mcp;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.livevalues.StubLiveValues;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.wikantik.livevalues.StubLiveValues.TOKEN;
import static com.wikantik.livevalues.StubLiveValues.VALUE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code read_pages} hands out reader text: live values, not tokens (summary field and body). */
class ReadPagesToolLiveValuesTest {

    private static String run( final StubLiveValues lv ) {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPageWithoutMetadata( eq( "A" ), anyInt() ) ).thenReturn( mock( Page.class ) );
        when( pm.getPureText( eq( "A" ), anyInt() ) ).thenReturn(
            "---\nsummary: Has " + TOKEN + " articles\n---\nBody " + TOKEN + " pages" );
        final ReadPagesTool tool = new ReadPagesTool( pm, null, PageViewGate.ALLOW_ALL, () -> lv );
        return ( ( McpSchema.TextContent ) tool.execute( Map.of( "slugs", List.of( "A" ) ) ).content().get( 0 ) ).text();
    }

    @Test
    void expandsSummaryAndBody() {
        final String json = run( new StubLiveValues() );
        assertTrue( json.contains( "Body " + VALUE + " pages" ), json );
        assertTrue( json.contains( "summary: Has " + VALUE + " articles" ), json );
        assertFalse( json.contains( "{{wiki:" ), json );
    }

    @Test
    void withoutServiceTokensStayVerbatim() {
        assertTrue( run( null ).contains( "{{wiki:articles" ) );
    }
}
