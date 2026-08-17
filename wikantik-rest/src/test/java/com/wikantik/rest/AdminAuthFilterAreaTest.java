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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Area resolution for scoped {@code /admin/*} grants.
 *
 * <p>This is security-relevant string handling: whatever {@code adminAreaOf} returns is what a
 * grant is matched against, so an input that resolves to an unexpected area would let a narrow
 * grant reach a surface it was never given.</p>
 */
class AdminAuthFilterAreaTest {

    @Test
    void resolvesTheFirstSegmentAfterAdmin() {
        assertEquals( "insights", AdminAuthFilter.adminAreaOf( "/admin/insights/ingest", "" ) );
        assertEquals( "insights", AdminAuthFilter.adminAreaOf( "/admin/insights", "" ) );
        assertEquals( "users", AdminAuthFilter.adminAreaOf( "/admin/users/bob", "" ) );
        assertEquals( "connector-credentials",
                AdminAuthFilter.adminAreaOf( "/admin/connector-credentials/1", "" ) );
    }

    @Test
    void stripsTheContextPath() {
        assertEquals( "insights", AdminAuthFilter.adminAreaOf( "/wiki/admin/insights/ingest", "/wiki" ) );
    }

    /** No area segment means no scoped grant can apply — the request falls back to AllPermission. */
    @Test
    void pathsWithoutAnAreaResolveToNull() {
        assertNull( AdminAuthFilter.adminAreaOf( "/admin", "" ) );
        assertNull( AdminAuthFilter.adminAreaOf( "/admin/", "" ) );
        assertNull( AdminAuthFilter.adminAreaOf( "/api/pages", "" ) );
        assertNull( AdminAuthFilter.adminAreaOf( null, "" ) );
    }

    /**
     * A traversal segment must never resolve to an area. {@code /admin/../admin/users} reaching a
     * grant for something other than {@code users} would be a scope-escape.
     */
    @Test
    void traversalAndQualifiedSegmentsAreRefused() {
        assertNull( AdminAuthFilter.adminAreaOf( "/admin/../users", "" ) );
        assertNull( AdminAuthFilter.adminAreaOf( "/admin/..", "" ) );
        assertNull( AdminAuthFilter.adminAreaOf( "/admin/wiki:insights", "" ),
                "a colon would be parsed as a wiki qualifier by AdminPermission" );
    }

    @Test
    void areaIsLowercased() {
        assertEquals( "insights", AdminAuthFilter.adminAreaOf( "/admin/INSIGHTS/ingest", "" ) );
    }

    /**
     * Every {@code /admin/*} servlet mapping must resolve to a non-null area, or that endpoint can
     * never be reached by a scoped grant and is silently AllPermission-only forever. That is a safe
     * failure (it denies), but it should be a deliberate one, so it is asserted here rather than
     * discovered when someone wonders why their grant does nothing.
     */
    @Test
    void everyAdminServletMappingResolvesToAnArea() throws IOException {
        final Path webXml = Path.of( "..", "wikantik-war", "src", "main", "webapp", "WEB-INF", "web.xml" );
        if ( !Files.exists( webXml ) ) {
            return; // not laid out as expected in this run; the IT suite covers the wiring
        }
        final String xml = Files.readString( webXml, StandardCharsets.UTF_8 );
        final Matcher m = Pattern.compile( "<url-pattern>(/admin/[^<]*)</url-pattern>" ).matcher( xml );

        final Set< String > unresolvable = new LinkedHashSet<>();
        final Set< String > areas = new LinkedHashSet<>();
        int seen = 0;
        while ( m.find() ) {
            seen++;
            final String pattern = m.group( 1 );
            // Strip the trailing wildcard so "/admin/users/*" is tested as a concrete request.
            final String concrete = pattern.endsWith( "/*" )
                    ? pattern.substring( 0, pattern.length() - 2 ) + "/x"
                    : pattern;
            final String area = AdminAuthFilter.adminAreaOf( concrete, "" );
            if ( area == null ) {
                unresolvable.add( pattern );
            } else {
                areas.add( area );
            }
        }

        assertTrue( seen > 0, "expected to find /admin/* url-patterns in web.xml" );
        assertTrue( unresolvable.isEmpty(), "admin mappings with no resolvable area: " + unresolvable );
        assertTrue( areas.contains( "insights" ), "sanity: the insights area should be present" );
        assertFalse( areas.contains( "*" ), "a wildcard must never become an area name" );
    }
}
