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
package com.wikantik.mcp.resources;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.livevalues.StubLiveValues;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static com.wikantik.livevalues.StubLiveValues.TOKEN;
import static com.wikantik.livevalues.StubLiveValues.VALUE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The {@code wiki://pages/{pageName}} resource is a reader surface: values, not tokens. */
class WikiResourcesLiveValuesTest {

    private static String read( final WikiResources resources ) {
        final var spec = resources.resourceTemplates().stream()
                .filter( s -> s.resourceTemplate().uriTemplate().equals( "wiki://pages/{pageName}" ) )
                .findFirst().orElseThrow();
        final var uri = "wiki://pages/Main";
        return ( ( McpSchema.TextResourceContents ) spec.readHandler()
                .apply( null, new McpSchema.ReadResourceRequest( uri ) ).contents().get( 0 ) ).text();
    }

    private static WikiResources resources( final StubLiveValues lv ) {
        final PageManager pm = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "Main" );
        when( page.getLastModified() ).thenReturn( new Date( 0 ) );
        when( pm.getPage( eq( "Main" ), anyInt() ) ).thenReturn( page );
        when( pm.getPureText( eq( "Main" ), anyInt() ) ).thenReturn(
            "---\nsummary: Has " + TOKEN + " articles\n---\nBody " + TOKEN + " pages" );
        return new WikiResources( pm, mock( ReferenceManager.class ), mock( AttachmentManager.class ),
                mock( SystemPageRegistry.class ), () -> lv );
    }

    @Test
    void pageResourceExpandsContentAndSummary() {
        final String json = read( resources( new StubLiveValues() ) );
        assertTrue( json.contains( "Body " + VALUE + " pages" ), json );
        assertTrue( json.contains( "Has " + VALUE + " articles" ), json );
        assertFalse( json.contains( "{{wiki:" ), json );
    }

    @Test
    void withoutLiveValuesTokensStayVerbatim() {
        assertTrue( read( resources( null ) ).contains( "{{wiki:articles" ) );
    }
}
