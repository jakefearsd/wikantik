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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No table stores a computed metric: the flow and DORA classes read, and register nothing with a metrics library. */
class WorkFlowNoStoreRuleTest {

    private static final Path MAIN = Path.of( "src", "main", "java", "com", "wikantik", "work" );
    private static final Pattern WRITE = Pattern.compile( "\\b(INSERT|UPDATE|DELETE|CREATE|ALTER|TRUNCATE)\\b",
        Pattern.CASE_INSENSITIVE );
    private static final Pattern STRING = Pattern.compile( "\"\"\"(?s:.*?)\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\"" );

    /** Every class that computes or reads a figure; absent files are not skipped: the list is asserted. */
    private static final List< String > FIGURE_CLASSES = List.of( "JdbcWorkFlow", "WorkFlowService", "WorkFlowStore",
        "Flow", "FlowQuery", "FlowReport", "Percentiles", "Durations", "MetricWindow", "Dora", "DoraReport",
        "DoraReports", "DoraQuery" );

    private static String source( final String name ) throws IOException {
        return Files.readString( MAIN.resolve( name + ".java" ) );
    }

    /** The source with {@code /* *}{@code /} and {@code //} comments removed. */
    static String withoutComments( final String source ) {
        return source.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "(?m)//.*$", "" );
    }

    private static List< String > literals( final String source ) {
        final List< String > out = new ArrayList<>();
        final Matcher m = STRING.matcher( withoutComments( source ) );
        while ( m.find() ) {
            out.add( m.group() );
        }
        return out;
    }

    @Test
    void theFlowStoreOnlyReads() throws IOException {
        final String source = source( "JdbcWorkFlow" );
        final List< String > writes = literals( source ).stream().filter( l -> WRITE.matcher( l ).find() ).toList();
        assertEquals( List.of(), writes, "a statement in the flow store that is not a read" );
        assertTrue( literals( source ).stream().anyMatch( l -> l.contains( "JOIN LATERAL" ) ), "the scan sees text blocks" );
        final String code = withoutComments( source );
        assertFalse( code.contains( "jdbc.update" ) || code.contains( "inTransaction" ), "no write call" );
    }

    @Test
    void aWordInACommentCannotFailTheScan() {
        assertEquals( "int a = 1; \n", withoutComments( "int a = 1; // update the row\n" ).replaceAll( "\\s+$", " \n" ) );
        assertEquals( "x ", withoutComments( "x /* UPDATE and INSERT */ " ).replace( "  ", " " ) );
    }

    @Test
    void noFlowClassRegistersAMetric() throws IOException {
        final List< String > offenders = new ArrayList<>();
        for ( final String name : FIGURE_CLASSES ) {
            final String code = withoutComments( source( name ) );
            if ( code.contains( "io.micrometer" ) || code.contains( "WorkListener" ) ) {
                offenders.add( name );
            }
        }
        assertEquals( List.of(), offenders );
    }
}
