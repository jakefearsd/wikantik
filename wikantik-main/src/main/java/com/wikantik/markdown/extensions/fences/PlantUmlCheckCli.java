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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /**
     * Holder so log4j is first touched after {@link #run} has pointed {@code System.out} at stderr: its default
     * console appender binds {@code System.out} at initialisation, and stdout is reserved for the report.
     */
    private static final class Log {
        static final Logger LOG = LogManager.getLogger( PlantUmlCheckCli.class );
    }

    private static final String USAGE = "usage: bin/check-plantuml.sh [--json] <file|dir|->...";

    private PlantUmlCheckCli() {
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
    @SuppressWarnings( "PMD.CloseResource" ) // realOut is saved only to be restored, never closed: it is the process stdout
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
            Log.LOG.warn( "check-plantuml failed: {}", e.toString(), e );
            err.println( "check-plantuml: " + e );
            return 2;
        } finally {
            System.setOut( realOut );
        }
    }

    private static int check( final String[] args, final InputStream in, final PrintStream out, final PrintStream err )
            throws UsageException, IOException {
        final boolean json = Arrays.asList( args ).contains( "--json" );
        final List< String > targets = targetsOf( args );
        final List< PlantUmlCheckReport.Diagram > results = PlantUmlDiagramChecker.checkAll( targets, in );
        if ( json ) {
            out.println( PlantUmlCheckReport.json( results ) );
        } else {
            PlantUmlCheckReport.printText( results, out );
        }
        return results.stream().anyMatch( d -> !d.ok() ) ? 1 : 0;
    }

    /** The file/dir/{@code -} arguments, validated; throws a usage fault for an unknown option, no target or a missing path. */
    private static List< String > targetsOf( final String[] args ) throws UsageException {
        final List< String > targets = new ArrayList<>();
        for ( final String a : args ) {
            if ( a.startsWith( "--" ) && !"--json".equals( a ) ) {
                throw new UsageException( "unknown option " + a );
            }
            if ( !a.startsWith( "--" ) ) {
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
        return targets;
    }

}
