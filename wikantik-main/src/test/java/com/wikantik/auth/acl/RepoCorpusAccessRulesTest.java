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
package com.wikantik.auth.acl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No page the repository ships has access rules the reader locks (#104). The scope is exactly the two shipped page
 * roots, {@code docs/wikantik-pages} and {@code wikantik-wikipages}; the integration-test fixture repository
 * ({@code wikantik-it-tests/.../test-repo/}, which deliberately holds a locked page) is outside both and never read.
 */
class RepoCorpusAccessRulesTest {

    /** Root to the fewest pages it must hold: an empty or misrouted root must not hide behind the other. */
    private static final Map< String, Integer > ROOT_FLOORS = Map.of( "docs/wikantik-pages", 1000, "wikantik-wikipages", 10 );

    @Test
    void noShippedPageIsLocked() throws IOException {
        final Path repo = Path.of( System.getProperty( "user.dir" ) ).getParent();
        final List< String > locked = new ArrayList<>();
        for ( final Map.Entry< String, Integer > rootFloor : ROOT_FLOORS.entrySet() ) {
            final String root = rootFloor.getKey();
            int pages = 0;
            final Path dir = repo.resolve( root );
            try ( Stream< Path > files = Files.walk( dir ) ) {
                for ( final Path file : files.filter( RepoCorpusAccessRulesTest::isShippedPage ).sorted().toList() ) {
                    pages++;
                    if ( AccessRules.read( Files.readString( file ) ) instanceof AccessRules.Locked l ) {
                        locked.add( repo.relativize( file ) + " " + l.reasons() );
                    }
                }
            }
            assertTrue( pages > rootFloor.getValue(), root + " was read: " + pages + " pages" );
        }
        assertEquals( List.of(), locked );
    }

    private static boolean isShippedPage( final Path p ) {
        final String s = p.toString().replace( '\\', '/' );
        return s.endsWith( ".md" ) && !s.contains( "/target/" ) && !s.contains( "/test-repo/" );
    }
}
