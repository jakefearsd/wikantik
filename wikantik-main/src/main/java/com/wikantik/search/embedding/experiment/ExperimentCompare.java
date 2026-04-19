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
package com.wikantik.search.embedding.experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prints a compact side-by-side table of overall metrics extracted from one or
 * more reports produced by {@link ExperimentEvaluator}. Does not re-run any
 * evaluation — just greps the numeric summary out of each file.
 */
public final class ExperimentCompare {

    private static final Pattern HEADER_MODEL = Pattern.compile( "^Retrieval evaluation — model:\\s+(\\S+)" );
    private static final Pattern OVERALL_ROW  = Pattern.compile(
        "^\\s+(bm25|dense|hybrid)\\s+([0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s*$" );

    private ExperimentCompare() {}

    public static void main( final String[] args ) throws IOException {
        if( args.length == 0 ) {
            System.err.println( """
                Usage: ExperimentCompare <report.txt> [report.txt …]
                  Prints a side-by-side table of each report's overall metrics.
                """ );
            System.exit( 2 );
        }
        final List< Summary > summaries = new ArrayList<>();
        for( final String a : args ) summaries.add( parse( Paths.get( a ) ) );

        System.out.printf( Locale.ROOT, "%-30s  %-8s  %8s  %8s  %8s%n",
            "model", "retriever", "recall@5", "recall@20", "MRR" );
        for( final Summary s : summaries ) {
            for( final Map.Entry< String, double[] > e : s.metrics().entrySet() ) {
                System.out.printf( Locale.ROOT, "%-30s  %-8s  %8.3f  %8.3f  %8.3f%n",
                    s.model(), e.getKey(), e.getValue()[ 0 ], e.getValue()[ 1 ], e.getValue()[ 2 ] );
            }
        }
    }

    record Summary( String model, Map< String, double[] > metrics ) {}

    static Summary parse( final Path file ) throws IOException {
        String model = file.getFileName().toString();
        final Map< String, double[] > metrics = new LinkedHashMap<>();
        for( final String line : Files.readAllLines( file ) ) {
            final Matcher mh = HEADER_MODEL.matcher( line );
            if( mh.find() ) {
                model = mh.group( 1 );
                continue;
            }
            final Matcher mr = OVERALL_ROW.matcher( line );
            if( mr.find() ) {
                metrics.put( mr.group( 1 ), new double[] {
                    Double.parseDouble( mr.group( 2 ) ),
                    Double.parseDouble( mr.group( 3 ) ),
                    Double.parseDouble( mr.group( 4 ) )
                } );
            }
        }
        return new Summary( model, metrics );
    }
}
