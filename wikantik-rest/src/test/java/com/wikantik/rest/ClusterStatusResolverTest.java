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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.wikantik.WikiSubsystems;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Direct tests of {@link ClusterStatusResolver}: the derived {@code cluster_status} block of the page payload. */
class ClusterStatusResolverTest {

    private final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
    private RestServletBase servlet;
    private PageGraphSubsystem.Services pageGraph;
    private ClusterStatusResolver resolver;

    @BeforeEach
    void setUp() {
        servlet = Mockito.mock( RestServletBase.class );
        pageGraph = Mockito.mock( PageGraphSubsystem.Services.class );
        final WikiSubsystems subs = Mockito.mock( WikiSubsystems.class );
        Mockito.doReturn( pageGraph ).when( subs ).pageGraph();
        Mockito.doReturn( subs ).when( servlet ).getSubsystems();
        // by default every page is viewable
        Mockito.doAnswer( inv -> Set.copyOf( inv.< java.util.Collection< String > >getArgument( 1 ) ) )
                .when( servlet ).filterViewable( Mockito.any(), Mockito.any() );
        resolver = new ClusterStatusResolver( servlet );
    }

    private static PageDescriptor page( final String slug ) {
        return new PageDescriptor( "ID" + slug, slug, slug, PageType.ARTICLE, "c", List.of(), List.of(), null,
                Instant.now(), false, List.of() );
    }

    private static ClusterDetails cluster( final String name, final PageDescriptor hub, final PageDescriptor... articles ) {
        return new ClusterDetails( name, hub, List.of( articles ), Map.of(), Instant.now() );
    }

    private StructuralIndexService index() {
        final StructuralIndexService idx = Mockito.mock( StructuralIndexService.class );
        Mockito.doReturn( idx ).when( pageGraph ).structuralIndexService();
        return idx;
    }

    @Test
    void noClusterYieldsNull() {
        assertNull( resolver.resolve( request, Map.of() ) );
        assertNull( resolver.resolve( request, null ) );
        assertNull( resolver.resolve( request, Map.of( "cluster", "" ) ) );
    }

    @Test
    void declaredClusterReportsHubAndMemberCount() {
        final StructuralIndexService idx = index();
        Mockito.doReturn( Optional.of( cluster( "a", page( "AHub" ), page( "A1" ), page( "A2" ) ) ) )
                .when( idx ).getCluster( "a" );
        final JsonObject out = resolver.resolve( request, Map.of( "cluster", "a" ) );
        assertEquals( "a", out.get( "path" ).getAsString() );
        assertEquals( "AHub", out.get( "hub_slug" ).getAsString() );
        assertTrue( out.get( "hub_declared" ).getAsBoolean() );
        assertEquals( 2, out.get( "member_count" ).getAsInt() );
        assertFalse( out.has( "memberships" ) );
    }

    @Test
    void unknownClusterIsUndeclaredButKeepsItsPath() {
        final StructuralIndexService idx = index();
        Mockito.doReturn( Optional.empty() ).when( idx ).getCluster( "ghost" );
        final JsonObject out = resolver.resolve( request, Map.of( "cluster", "ghost" ) );
        assertEquals( "ghost", out.get( "path" ).getAsString() );
        assertFalse( out.get( "hub_declared" ).getAsBoolean() );
        assertTrue( out.get( "hub_slug" ).isJsonNull() );
        assertEquals( 0, out.get( "member_count" ).getAsInt() );
    }

    @Test
    void absentIndexDegradesToUndeclared() {
        Mockito.doReturn( null ).when( pageGraph ).structuralIndexService();
        final JsonObject out = resolver.resolve( request, Map.of( "cluster", "a" ) );
        assertFalse( out.get( "hub_declared" ).getAsBoolean() );
    }

    @Test
    void failingIndexDegradesToUndeclared() {
        final StructuralIndexService idx = index();
        Mockito.doThrow( new IllegalStateException( "warming" ) ).when( idx ).getCluster( "a" );
        final JsonObject out = resolver.resolve( request, Map.of( "cluster", "a" ) );
        assertEquals( "a", out.get( "path" ).getAsString() );
        assertFalse( out.get( "hub_declared" ).getAsBoolean() );
        assertEquals( 0, out.get( "member_count" ).getAsInt() );
    }

    @Test
    void membersAndHubTheCallerCannotViewAreHiddenFromTheCount() {
        final StructuralIndexService idx = index();
        Mockito.doReturn( Optional.of( cluster( "a", page( "AHub" ), page( "Open" ), page( "Secret" ) ) ) )
                .when( idx ).getCluster( "a" );
        Mockito.doReturn( Set.of( "Open" ) ).when( servlet ).filterViewable( Mockito.any(), Mockito.any() );
        final JsonObject out = resolver.resolve( request, Map.of( "cluster", "a" ) );
        assertFalse( out.get( "hub_declared" ).getAsBoolean(), "an unviewable hub is absent" );
        assertEquals( 1, out.get( "member_count" ).getAsInt() );
    }

    @Test
    void multipleMembershipsAreListedWithoutCounts() {
        final StructuralIndexService idx = index();
        Mockito.doReturn( Optional.of( cluster( "a", page( "AHub" ), page( "A1" ) ) ) ).when( idx ).getCluster( "a" );
        Mockito.doReturn( Optional.empty() ).when( idx ).getCluster( "b" );
        final JsonObject out = resolver.resolve( request, Map.of( "cluster", List.of( "a", "b" ) ) );
        assertEquals( "a", out.get( "path" ).getAsString(), "the first membership stays the primary" );
        final var all = out.getAsJsonArray( "memberships" );
        assertEquals( 2, all.size() );
        assertEquals( "a", all.get( 0 ).getAsJsonObject().get( "path" ).getAsString() );
        assertTrue( all.get( 0 ).getAsJsonObject().get( "hub_declared" ).getAsBoolean() );
        assertEquals( "b", all.get( 1 ).getAsJsonObject().get( "path" ).getAsString() );
        assertFalse( all.get( 1 ).getAsJsonObject().get( "hub_declared" ).getAsBoolean() );
    }
}
