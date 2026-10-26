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

import com.wikantik.markdown.extensions.fences.PlantUmlCheckReport.Diagram;
import com.wikantik.plantuml.PlantUmlSandbox;
import com.wikantik.plantuml.PlantUmlService;
import com.wikantik.plantuml.PlantUmlSettings;
import com.wikantik.plantuml.RenderOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Reads the targets of {@link PlantUmlCheckCli} and draws every non-example {@code plantuml} fence. */
final class PlantUmlDiagramChecker {

    private PlantUmlDiagramChecker() {
    }

    static List< Diagram > checkAll( final List< String > targets, final InputStream in )
            throws IOException {
        return checkAll( targets, in, PlantUmlDiagramChecker::newRenderer );
    }

    /** {@link #checkAll(List, InputStream)} over a given renderer factory, built lazily on the first diagram (test seam). */
    static List< Diagram > checkAll( final List< String > targets, final InputStream in,
                                     final Supplier< Function< String, RenderOutcome > > rendererFactory )
            throws IOException {
        final Map< String, String > inputs = new LinkedHashMap<>();
        for ( final String t : targets ) {
            if ( "-".equals( t ) ) {
                inputs.put( "-", new String( in.readAllBytes(), StandardCharsets.UTF_8 ) );
            } else {
                for ( final Path f : markdownFiles( Path.of( t ) ) ) {
                    inputs.put( f.toString(), Files.readString( f ) );
                }
            }
        }
        final List< Diagram > results = new ArrayList<>();
        Function< String, RenderOutcome > renderer = null;
        for ( final Map.Entry< String, String > input : inputs.entrySet() ) {
            for ( final ScannedFence f : RenderedFenceScanner.scan( input.getValue() ) ) {
                if ( !"plantuml".equals( f.info().language() ) || f.info().example() ) {
                    continue;
                }
                if ( renderer == null ) {
                    renderer = rendererFactory.get();
                }
                results.add( new Diagram( input.getKey(), f.line(), errorOf( draw( renderer, f.source() ), f.line() ) ) );
            }
        }
        return results;
    }

    private static Function< String, RenderOutcome > newRenderer() {
        if ( !PlantUmlSandbox.ensure() ) {
            throw new IllegalStateException( "the PlantUML sandbox could not be confirmed" );
        }
        final PlantUmlService service = new PlantUmlService( PlantUmlSettings.defaults() );
        final long budget = PlantUmlSettings.defaults().timeoutMs();
        return source -> service.render( source, budget );
    }

    /** Renders with a full timeout as the wait budget; a transient refusal (e.g. busy) is retried once. */
    private static RenderOutcome draw( final Function< String, RenderOutcome > renderer, final String source ) {
        final RenderOutcome first = renderer.apply( source );
        return first.transientFailure() ? renderer.apply( source ) : first;
    }

    /** The error text; a PlantUML error line is source-relative (1 = first content line), so the file line is fence line + N. */
    private static String errorOf( final RenderOutcome o, final int fenceLine ) {
        if ( o.isDrawn() ) {
            return null;
        }
        final StringBuilder sb = new StringBuilder( o.reason() == null ? "render-failed" : o.reason() );
        if ( o.line() > 0 ) {
            sb.append( " line " ).append( fenceLine + o.line() );
        }
        final String msg = o.message() == null ? "" : o.message().lines().filter( l -> !l.isBlank() ).findFirst().orElse( "" ).trim();
        if ( !msg.isEmpty() ) {
            sb.append( ": " ).append( msg );
        }
        return sb.toString();
    }

    private static boolean isMarkdown( final Path p ) {
        final Path name = p.getFileName();
        return name != null && name.toString().endsWith( ".md" );
    }

    private static List< Path > markdownFiles( final Path path ) throws IOException {
        if ( !Files.isDirectory( path ) ) {
            return List.of( path );
        }
        try ( Stream< Path > walk = Files.walk( path ) ) {
            return walk.filter( p -> Files.isRegularFile( p ) && isMarkdown( p ) )
                    .sorted( Comparator.comparing( Path::toString ) ).toList();
        }
    }
}
