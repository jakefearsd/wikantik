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
 * Integration tests for the {@code list_pages} MCP tool.
 */
public class ListPagesIT extends WithMcpTestSetup {

    @Test
    public void listAllPages() {
        final Map< String, Object > result = mcp.listPages();
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        Assertions.assertFalse( pages.isEmpty(), "Should have at least the pre-seeded pages" );

        // Main is a system page and may be filtered by list_pages; just verify we have pages
        Assertions.assertTrue( pages.size() >= 1, "Should list at least one page" );

        // Check all fields present
        final Map< String, Object > firstPage = pages.get( 0 );
        Assertions.assertNotNull( firstPage.get( "name" ), "name field should be present" );
        Assertions.assertTrue( firstPage.containsKey( "lastModified" ), "lastModified field should be present" );
        Assertions.assertTrue( firstPage.containsKey( "author" ), "author field should be present" );
        Assertions.assertTrue( firstPage.containsKey( "size" ), "size field should be present" );
    }

    @Test
    public void listPagesWithPrefix() {
        final String prefix = "ListPfx" + System.currentTimeMillis();
        mcp.writePage( prefix + "Alpha", "Alpha" );
        mcp.writePage( prefix + "Beta", "Beta" );
        mcp.writePage( "OtherPage" + System.currentTimeMillis(), "Other" );

        final Map< String, Object > result = mcp.listPages( prefix );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        Assertions.assertEquals( 2, pages.size(), "Should return exactly 2 pages with prefix" );
        Assertions.assertTrue( pages.stream().allMatch( p -> ( ( String ) p.get( "name" ) ).startsWith( prefix ) ) );
    }

    @Test
    public void listPagesRespectsLimit() {
        final Map< String, Object > result = mcp.listPages( null, 2 );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        Assertions.assertTrue( pages.size() <= 2, "Should return at most 2 pages" );
    }

    @Test
    public void listPagesAreSortedByName() {
        final Map< String, Object > result = mcp.listPages();
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        for ( int i = 1; i < pages.size(); i++ ) {
            final String prev = ( String ) pages.get( i - 1 ).get( "name" );
            final String curr = ( String ) pages.get( i ).get( "name" );
            Assertions.assertTrue( prev.compareTo( curr ) <= 0,
                    "Pages should be sorted: '" + prev + "' should come before '" + curr + "'" );
        }
    }
}
