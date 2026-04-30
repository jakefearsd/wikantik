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
package com.wikantik.mcp.tools;

import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarkPageVerifiedToolTest {

    @Test
    void name_isMarkPageVerified() {
        final MarkPageVerifiedTool tool = new MarkPageVerifiedTool(
            mock( PageSaveHelper.class ), mock( PageManager.class ), null );
        assertEquals( "mark_page_verified", tool.name() );
    }

    @Test
    void execute_refusesSystemPage() throws Exception {
        // Verification metadata is a trust signal — the /for-agent projection treats
        // confidence: stale as untrusted, but pinned/computed authoritative as trustworthy.
        // Letting agents stamp shipped system pages would be a soft attack on those signals.
        final PageManager pm = mock( PageManager.class );
        final PageSaveHelper helper = mock( PageSaveHelper.class );
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.isSystemPage( "CSSRibbon" ) ).thenReturn( true );
        when( sys.isSystemPage( "Main" ) ).thenReturn( true );

        final MarkPageVerifiedTool tool = new MarkPageVerifiedTool( helper, pm, sys );
        tool.setDefaultAuthor( "bot" );

        final McpSchema.CallToolResult result = tool.execute( Map.of(
            "pageNames", List.of( "CSSRibbon", "Main" ) ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "system page" ),
            "refusal reason must surface so the agent stops retrying" );
        assertTrue( text.contains( "\"succeeded\":0" ),
            "no system pages should be marked verified" );
        verify( helper, never() ).saveText( anyString(), anyString(), any( SaveOptions.class ) );
        // Refusal happens before the page lookup — no point hitting PageManager.
        verify( pm, never() ).getPage( eq( "CSSRibbon" ) );
        verify( pm, never() ).getPage( eq( "Main" ) );
    }
}
