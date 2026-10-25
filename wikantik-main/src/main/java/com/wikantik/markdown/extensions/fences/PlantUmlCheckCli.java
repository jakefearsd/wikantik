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
import com.wikantik.plantuml.PlantUmlSandbox;
import com.wikantik.plantuml.PlantUmlService;
import com.wikantik.plantuml.PlantUmlSettings;
import com.wikantik.plantuml.RenderOutcome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Corpus checker for PlantUML fences, the counterpart of {@code wikantik-frontend/scripts/check-mermaid.mjs} and
 * run through {@code bin/check-plantuml.sh}. For every non-{@code example} {@code plantuml} fence in the given
 * Markdown it draws the diagram with the wiki's own server-side pipeline (policy, sandbox, scrub) and reports it.
 *
 * <p>Usage: {@code [--json] <file|dir|->...} where a directory means every {@code *.md} under it and {@code -} is
 * Markdown on stdin. Output is one line per diagram, {@code OK|FAIL <file>:<line> <error>} (line = the opening fence,
 * 1-based) plus a summary line; with {@code --json} stdout carries only an array of {@code {file, line, ok, error}}.
 * Everything else (PlantUML and logging noise) goes to stderr. This contract is shared with the content agent's
 * linter. Exit: 0 all OK or no diagrams, 1 any FAIL, 2 usage or checker error.
 */
public final class PlantUmlCheckCli {

    private static final Logger LOG = LogManager.getLogger( PlantUmlCheckCli.class );
    private static final String USAGE = "usage: bin/check-plantuml.sh [--json] <file|dir|->...";

    private PlantUmlCheckCli() {
    }

    /** One checked diagram. */
    private record Diagram( String file, int line, String error ) {
        boolean ok() {
            return error == null;
        }
    }

    /** A usage fault: reported with the usage line, exit 2. */
    private static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException( final String message ) {
            super( message );
        }
    }

    public static void main( final String[] args ) {
        System.exit( run( args, System.in, System.out, System.err ) );
    }

    /**
     * Runs the checker.
     *
     * @return the process exit code: 0 all OK (or none), 1 any diagram failed, 2 usage or checker error
     */
    public static int run( final String[] args, final InputStream in, final PrintStream out, final PrintStream err ) {
        final PrintStream realOut = System.out;
        // stdout is reserved for the report; any library chatter belongs on stderr.
        System.setOut( err );
        try {
            return check( args, in, out, err );
        } catch ( final UsageException e ) {
            if ( !e.getMessage().isEmpty() ) {
                err.println( e.getMessage() );
            }
            err.println( USAGE );
            return 2;
        } catch ( final IOException | RuntimeException | LinkageError e ) {
            LOG.warn( "check-plantuml failed: {}", e.toString(), e );
            err.println( "check-plantuml: " + e );
            return 2;
        } finally {
            System.setOut( realOut );
        }
    }

    private static int check( final String[] args, final InputStream in, final PrintStream out, final PrintStream err )
            throws UsageException, IOException {
        boolean json = false;
        final List< String > targets = new ArrayList<>();
        for ( final String a : args ) {
            if ( "--json".equals( a ) ) {
                json = true;
            } else if ( a.startsWith( "--" ) ) {
                throw new UsageException( "unknown option " + a );
            } else {
                targets.add( a );
            }
        }
        if ( targets.isEmpty() ) {
            throw new UsageException( "" );
        }
        for ( final String t : targets ) {
            if ( !"-".equals( t ) && !Files.exists( Path.of( t ) ) ) {
                throw new UsageException( "no such file or directory: " + t );
            }
        }
        final List< Diagram > results = checkAll( targets, in, err );
        if ( json ) {
            final List< Map< String, Object > > rows = new ArrayList<>();
            for ( final Diagram d : results ) {
                final Map< String, Object > row = new LinkedHashMap<>();
                row.put( "file", d.file() );
                row.put( "line", d.line() );
                row.put( "ok", d.ok() );
                row.put( "error", d.error() );
                rows.add( row );
            }
            out.println( new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson( rows ) );
        } else {
            for ( final Diagram d : results ) {
                out.println( ( d.ok() ? "OK " : "FAIL " ) + d.file() + ":" + d.line() + ( d.ok() ? "" : " " + d.error() ) );
            }
            final long bad = results.stream().filter( d -> !d.ok() ).count();
            out.println( results.size() + " diagrams, " + ( results.size() - bad ) + " ok, " + bad + " failed" );
        }
        return results.stream().anyMatch( d -> !d.ok() ) ? 1 : 0;
    }

    private static List< Diagram > checkAll( final List< String > targets, final InputStream in, final PrintStream err )
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
        PlantUmlService service = null;
        for ( final Map.Entry< String, String > input : inputs.entrySet() ) {
            for ( final ScannedFence f : RenderedFenceScanner.scan( input.getValue() ) ) {
                if ( !"plantuml".equals( f.info().language() ) || f.info().example() ) {
                    continue;
                }
                if ( service == null ) {
                    service = newService();
                }
                results.add( new Diagram( input.getKey(), f.line(), errorOf( draw( service, f.source() ) ) ) );
            }
        }
        return results;
    }

    private static PlantUmlService newService() throws IOException {
        if ( !PlantUmlSandbox.ensure() ) {
            throw new IOException( "the PlantUML sandbox could not be confirmed" );
        }
        return new PlantUmlService( PlantUmlSettings.defaults() );
    }

    /** Renders with a full timeout as the wait budget; a transient refusal (e.g. busy) is retried once. */
    private static RenderOutcome draw( final PlantUmlService service, final String source ) {
        final long budget = PlantUmlSettings.defaults().timeoutMs();
        final RenderOutcome first = service.render( source, budget );
        return first.transientFailure() ? service.render( source, budget ) : first;
    }

    private static String errorOf( final RenderOutcome o ) {
        if ( o.isDrawn() ) {
            return null;
        }
        final StringBuilder sb = new StringBuilder( o.reason() == null ? "render-failed" : o.reason() );
        if ( o.line() > 0 ) {
            sb.append( " line " ).append( o.line() );
        }
        final String msg = o.message() == null ? "" : o.message().lines().filter( l -> !l.isBlank() ).findFirst().orElse( "" ).trim();
        if ( !msg.isEmpty() ) {
            sb.append( ": " ).append( msg );
        }
        return sb.toString();
    }

    private static List< Path > markdownFiles( final Path path ) throws IOException {
        if ( !Files.isDirectory( path ) ) {
            return List.of( path );
        }
        try ( Stream< Path > walk = Files.walk( path ) ) {
            return walk.filter( p -> Files.isRegularFile( p ) && p.getFileName().toString().endsWith( ".md" ) )
                    .sorted( Comparator.comparing( Path::toString ) ).toList();
        }
    }
}
