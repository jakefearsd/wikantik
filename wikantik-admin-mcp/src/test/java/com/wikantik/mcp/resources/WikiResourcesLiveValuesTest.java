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

/** The admin-MCP page resources are authoring reads: they return the raw token, never its value. */
class WikiResourcesLiveValuesTest {

    private static String read( final WikiResources resources, final String template, final String uri ) {
        final var spec = resources.resourceTemplates().stream()
                .filter( s -> s.resourceTemplate().uriTemplate().equals( template ) )
                .findFirst().orElseThrow();
        return ( ( McpSchema.TextResourceContents ) spec.readHandler()
                .apply( null, new McpSchema.ReadResourceRequest( uri ) ).contents().get( 0 ) ).text();
    }

    private static WikiResources resources() {
        final PageManager pm = mock( PageManager.class );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "Main" );
        when( page.getLastModified() ).thenReturn( new Date( 0 ) );
        when( pm.getPage( eq( "Main" ), anyInt() ) ).thenReturn( page );
        when( pm.getPureText( eq( "Main" ), anyInt() ) ).thenReturn(
            "---\nsummary: Has " + TOKEN + " articles\n---\nBody " + TOKEN + " pages" );
        return new WikiResources( pm, mock( ReferenceManager.class ), mock( AttachmentManager.class ),
                mock( SystemPageRegistry.class ) );
    }

    @Test
    void currentVersionResourceReturnsRawToken() {
        final String json = read( resources(), "wiki://pages/{pageName}", "wiki://pages/Main" );
        assertTrue( json.contains( "{{wiki:articles|round" ), json );
        assertFalse( json.contains( VALUE ), json );
    }

    @Test
    void historicVersionResourceReturnsRawToken() {
        final String json = read( resources(), "wiki://pages/{pageName}/version/{version}", "wiki://pages/Main/version/1" );
        assertTrue( json.contains( "{{wiki:articles|round" ), json );
        assertFalse( json.contains( VALUE ), json );
    }
}
