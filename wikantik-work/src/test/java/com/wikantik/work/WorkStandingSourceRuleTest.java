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
package com.wikantik.work;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sponsor ceiling lives in {@link WorkStanding} and nowhere else: no other class of the module may ask a store
 * for a role or for the visible projects, or an agent's own member row would be read without its sponsor's. The
 * module has no ArchUnit, so the rule is pinned on the sources.
 */
class WorkStandingSourceRuleTest {

    /**
     * The intake store's reads of reporter rows ({@code reportableProjects}, {@code hasReporters}, and the retired
     * {@code reporterCandidates}) are reached only through {@code WorkStanding}: a call on anything but {@code standing()}
     * is an offender.
     */
    private static final Pattern REPORTER_READS =
        Pattern.compile( "(?<!standing\\(\\))\\.(reportableProjects|hasReporters|reporterCandidates)\\(" );

    private static final Path MAIN = Path.of( "src", "main", "java", "com", "wikantik", "work" );

    @Test
    void onlyWorkStandingAsksAStoreForRolesOrVisibleProjects() throws IOException {
        final List< String > offenders = new ArrayList<>();
        int scanned = 0;
        try ( Stream< Path > files = Files.walk( MAIN ) ) {
            for ( final Path file : files.filter( f -> f.toString().endsWith( ".java" ) ).toList() ) {
                final String name = file.getFileName().toString();
                if ( name.equals( "WorkStanding.java" ) || name.startsWith( "Jdbc" )
                        || name.equals( "WorkCatalogStore.java" ) || name.equals( "WorkIntakeStore.java" ) ) {
                    continue;
                }
                scanned++;
                final String source = Files.readString( file );
                if ( source.contains( "store.roleFor(" ) || source.contains( "store.visibleProjects(" )
                        || REPORTER_READS.matcher( source ).find() ) {
                    offenders.add( name );
                }
            }
        }
        assertTrue( scanned > 20, "the scan found the module's sources (" + scanned + ")" );
        assertEquals( List.of(), offenders, "these classes read a role or visibility past WorkStanding" );
    }
}
