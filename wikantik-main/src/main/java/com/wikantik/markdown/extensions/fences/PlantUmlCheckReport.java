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
package com.wikantik.markdown.extensions.fences;

import com.google.gson.GsonBuilder;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Formats {@link PlantUmlCheckCli} results: the text lines and the {@code --json} array. */
final class PlantUmlCheckReport {

    private PlantUmlCheckReport() {
    }

    /** One checked diagram; {@code error} is null when it drew. */
    record Diagram( String file, int line, String error ) {
        boolean ok() {
            return error == null;
        }
    }

    static String json( final List< Diagram > results ) {
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson( jsonRows( results ) );
    }

    private static List< Map< String, Object > > jsonRows( final List< Diagram > results ) {
        final List< Map< String, Object > > rows = new ArrayList<>();
        for ( final Diagram d : results ) {
            final Map< String, Object > row = new LinkedHashMap<>();
            row.put( "file", d.file() );
            row.put( "line", d.line() );
            row.put( "ok", d.ok() );
            row.put( "error", d.error() );
            rows.add( row );
        }
        return rows;
    }

    static void printText( final List< Diagram > results, final PrintStream out ) {
        for ( final Diagram d : results ) {
            out.println( ( d.ok() ? "OK " : "FAIL " ) + d.file() + ":" + d.line() + ( d.ok() ? "" : " " + d.error() ) );
        }
        final long bad = results.stream().filter( d -> !d.ok() ).count();
        out.println( results.size() + " diagrams, " + ( results.size() - bad ) + " ok, " + bad + " failed" );
    }

}
