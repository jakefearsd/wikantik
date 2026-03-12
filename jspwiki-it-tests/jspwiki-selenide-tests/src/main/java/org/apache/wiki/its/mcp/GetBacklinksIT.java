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
package org.apache.wiki.its.mcp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Integration tests for the {@code get_backlinks} MCP tool.
 */
public class GetBacklinksIT extends WithMcpTestSetup {

    @Test
    public void backlinksForPageWithKnownLinks() {
        final String target = uniquePageName( "BLTarget" );
        final String source = uniquePageName( "BLSource" );
        mcp.writePage( target, "Target page content" );
        mcp.writePage( source, "Links to [" + target + "]" );

        final Map< String, Object > result = mcp.getBacklinks( target );
        @SuppressWarnings( "unchecked" )
        final List< String > backlinks = ( List< String > ) result.get( "backlinks" );

        Assertions.assertTrue( backlinks.contains( source ),
                source + " should link to " + target + ", so " + target + " should have " + source + " as a backlink" );
    }

    @Test
    public void backlinksForPageWithNoLinks() {
        final String pageName = uniquePageName( "Isolated" );
        mcp.writePage( pageName, "No links to this page" );

        final Map< String, Object > result = mcp.getBacklinks( pageName );
        @SuppressWarnings( "unchecked" )
        final List< String > backlinks = ( List< String > ) result.get( "backlinks" );

        Assertions.assertTrue( backlinks.isEmpty(),
                "Newly created isolated page should have no backlinks" );
    }

    @Test
    public void backlinkAppearsAfterWritingLinkingPage() {
        final String target = uniquePageName( "BacklinkTarget" );
        final String source = uniquePageName( "BacklinkSource" );

        mcp.writePage( target, "Target page" );
        mcp.writePage( source, "This links to [" + target + "]" );

        final Map< String, Object > result = mcp.getBacklinks( target );
        @SuppressWarnings( "unchecked" )
        final List< String > backlinks = ( List< String > ) result.get( "backlinks" );

        Assertions.assertTrue( backlinks.contains( source ),
                "Source page should appear as a backlink for target" );
    }
}
