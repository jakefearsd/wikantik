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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integration tests for the {@code query_metadata} MCP tool.
 */
public class QueryMetadataIT extends WithMcpTestSetup {

    @Test
    public void queryByFieldAndValue() {
        final String suffix = String.valueOf( System.currentTimeMillis() );
        final String pageName = uniquePageName( "QMField" );
        mcp.writePage( pageName, "Howto body", Map.of( "type", "howto-" + suffix ) );

        final Map< String, Object > result = mcp.queryMetadata( "type", "howto-" + suffix );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        Assertions.assertFalse( pages.isEmpty(), "Should find at least one page" );
        Assertions.assertTrue( pages.stream().anyMatch( p -> pageName.equals( p.get( "name" ) ) ) );
    }

    @Test
    public void queryByFieldWithoutValue() {
        final String pageName = uniquePageName( "QMFieldOnly" );
        mcp.writePage( pageName, "Has category", Map.of( "category", "testing" ) );

        final Map< String, Object > result = mcp.queryMetadata( "category", null );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        Assertions.assertTrue( pages.stream().anyMatch( p -> pageName.equals( p.get( "name" ) ) ),
                "Page with 'category' field should be found" );
    }

    @Test
    public void queryByTypeShortcut() {
        final String suffix = String.valueOf( System.currentTimeMillis() );
        final String pageName = uniquePageName( "QMType" );
        mcp.writePage( pageName, "Reference body", Map.of( "type", "reference-" + suffix ) );

        final Map< String, Object > result = mcp.queryMetadataByType( "reference-" + suffix );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        Assertions.assertTrue( pages.stream().anyMatch( p -> pageName.equals( p.get( "name" ) ) ),
                "Type shortcut should find the page" );
    }

    @Test
    public void queryWithNoMatchReturnsEmptyList() {
        final Map< String, Object > result = mcp.queryMetadata( "type", "nonexistent_type_" + System.currentTimeMillis() );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        Assertions.assertNotNull( pages );
        Assertions.assertTrue( pages.isEmpty(), "No match should return empty list, not error" );
    }

    @Test
    public void queryRequiresFieldOrType() {
        final Map< String, Object > result = mcp.queryMetadataExpectingError( Map.of() );
        Assertions.assertNotNull( result.get( "error" ), "Should return error when no field/type given" );
    }

    @Test
    public void queryMetadataWithListValues() {
        final String suffix = String.valueOf( System.currentTimeMillis() );
        final String pageName = uniquePageName( "QMList" );
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "tags", List.of( "alpha-" + suffix, "beta", "gamma" ) );

        mcp.writePage( pageName, "Tagged content", metadata );

        final Map< String, Object > result = mcp.queryMetadata( "tags", "alpha-" + suffix );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > pages = ( List< Map< String, Object > > ) result.get( "pages" );

        Assertions.assertTrue( pages.stream().anyMatch( p -> pageName.equals( p.get( "name" ) ) ),
                "Should match on any element in a list field" );
    }
}
