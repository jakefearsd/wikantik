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
package com.wikantik.plantuml;

import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.ErrorUml;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.TitledDiagram;
import net.sourceforge.plantuml.core.Diagram;
import net.sourceforge.plantuml.core.ImageData;
import net.sourceforge.plantuml.error.PSystemError;
import net.sourceforge.plantuml.klimt.color.ColorMapper;
import net.sourceforge.plantuml.version.Version;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sandboxed PlantUML renderer. Produces raw, unscrubbed SVG (light and dark variants) from author source.
 * The diagram's layout is forced to the pure-Java Smetana engine. That pragma does not reach an embedded
 * {@code {{ … }}} sub-diagram, which {@link PlantUmlPolicy} therefore refuses; independently of the policy,
 * {@link PlantUmlSandbox#ensure()} points {@code GRAPHVIZ_DOT} at a non-existent path, so the Graphviz binary is
 * never executed even for a source that reaches this class directly.
 */
public final class PlantUmlEngine {

    private static final Logger LOG = LogManager.getLogger( PlantUmlEngine.class );
    private static final String PRAGMA = "!pragma layout smetana";
    private static final AtomicBoolean DISABLED_LOGGED = new AtomicBoolean();

    private PlantUmlEngine() {
    }

    /** @return PlantUML's version string */
    public static String version() {
        return Version.versionString();
    }

    /**
     * Renders {@code source} to SVG in both colour variants.
     *
     * @param source the author's diagram source, with or without {@code @startuml}/{@code @enduml}
     * @return the drawing, or a refusal; never throws
     */
    public static RenderOutcome render( final String source ) {
        if( !PlantUmlSandbox.ensure() ) {
            if( DISABLED_LOGGED.compareAndSet( false, true ) ) {
                LOG.error( "PlantUML sandbox is not active; every render is refused" );
            }
            return RenderOutcome.transientRefusal( "render-failed" );
        }
        try {
            if( isEmpty( source ) ) {
                // PlantUML would draw its welcome image (or an empty canvas) for an empty source; never show that.
                return RenderOutcome.refused( "syntax-error", "The diagram is empty", 0 );
            }
            final Prepared prepared = prepare( source );
            final BlockUml block = firstBlock( prepared.text() );
            if( block == null ) {
                return RenderOutcome.refused( "syntax-error", "No diagram found", 0 );
            }
            final Diagram diagram = block.getDiagram();
            if( diagram instanceof PSystemError error ) {
                return refusal( error, prepared );
            }
            if( !( diagram instanceof TitledDiagram ) ) {
                // Real UML diagrams (class, sequence, activity, state, description...) are TitledDiagrams.
                // Everything else that is not an error is an info/easter-egg diagram (version, license,
                // donors, colors, listfonts, welcome...) which must never be returned as a drawing.
                return RenderOutcome.refused( "unsupported-diagram", "This kind of diagram is not supported", 0 );
            }
            final Export light = export( diagram, new FileFormatOption( FileFormat.SVG ) );
            final RenderOutcome lightCrash = crashOutcome( light );
            if( lightCrash != null ) {
                return lightCrash;
            }
            final Export dark = export( diagram, new FileFormatOption( FileFormat.SVG ).withColorMapper( ColorMapper.DARK_MODE ) );
            final RenderOutcome darkCrash = crashOutcome( dark );
            if( darkCrash != null ) {
                return darkCrash;
            }
            return RenderOutcome.drawn( new PlantUmlDrawing( light.svg(), dark.svg() ) );
        } catch( final StackOverflowError e ) {
            LOG.info( "PlantUML diagram too complex (stack exhausted)" );
            return RenderOutcome.refused( "too-complex", "The diagram is too complex to draw", 0 );
        } catch( final LinkageError | RuntimeException | IOException e ) {
            LOG.warn( "PlantUML render failed unexpectedly: {}", e.toString(), e );
            return RenderOutcome.transientRefusal( "render-failed" );
        }
    }

    /**
     * PlantUML swallows exceptions thrown while laying out/exporting (including StackOverflowError) and
     * returns its own "has crashed" image instead, reporting it only through {@code ImageData.getStatus()}
     * (non-zero; 503 for a crash). That image must never be shown. The status is the structural signal;
     * the English crash text is a secondary check that also catches a changed status convention.
     *
     * @return a refusal when {@code export} is PlantUML's crash/error image, otherwise {@code null}
     */
    static RenderOutcome crashOutcome( final Export export ) {
        final boolean crashText = export.svg().contains( "An error has occurred : " ) && export.svg().contains( " has crashed." );
        if( export.status() == 0 && !crashText ) {
            return null;
        }
        if( export.svg().contains( "StackOverflowError" ) ) {
            LOG.info( "PlantUML diagram too complex (stack exhausted)" );
            return RenderOutcome.refused( "too-complex", "The diagram is too complex to draw", 0 );
        }
        LOG.warn( "PlantUML produced a crash/error image (status {}); refusing it", export.status() );
        return RenderOutcome.transientRefusal( "render-failed" );
    }

    record Export( String svg, int status ) {
    }

    /** @return true when the source has nothing but whitespace and {@code @startuml}/{@code @enduml} markers */
    private static boolean isEmpty( final String source ) {
        return source == null || source.lines().allMatch( l -> {
            final String t = l.strip();
            return t.isEmpty() || t.startsWith( "@startuml" ) || t.startsWith( "@enduml" );
        } );
    }

    private static BlockUml firstBlock( final String text ) {
        final List< BlockUml > blocks = new SourceStringReader( text ).getBlocks();
        return blocks.isEmpty() ? null : blocks.get( 0 );
    }

    private static Export export( final Diagram diagram, final FileFormatOption option ) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ImageData data = diagram.exportDiagram( out, 0, option );
        return new Export( out.toString( StandardCharsets.UTF_8 ), data == null ? 0 : data.getStatus() );
    }

    private static RenderOutcome refusal( final PSystemError error, final Prepared prepared ) {
        final ErrorUml first = error.getFirstError();
        final String message = first == null ? "Syntax error" : first.getError();
        // getPosition() is 0-based in the pragma-injected text; map back to the author's 1-based line.
        final int processed = error.getLineLocation().getPosition() + 1;
        final int line = prepared.toAuthorLine( processed );
        return RenderOutcome.refused( "syntax-error", message, line );
    }

    /** Source after pragma injection plus the bookkeeping to map error lines back. */
    private record Prepared( String text, int insertedAfterLine, int insertedLines ) {

        /** @return the author's 1-based line for a 1-based processed line, or 0 if it lies in injected text */
        int toAuthorLine( final int processedLine ) {
            if( processedLine <= insertedAfterLine ) {
                return processedLine;
            }
            final int mapped = processedLine - insertedLines;
            return mapped > insertedAfterLine ? mapped : 0;
        }
    }

    private static Prepared prepare( final String source ) {
        final String normalized = source.replace( "\r\n", "\n" ).replace( '\r', '\n' );
        final String[] lines = normalized.split( "\n", -1 );
        for( int i = 0; i < lines.length; i++ ) {
            if( lines[ i ].stripLeading().startsWith( "@startuml" ) ) {
                final StringBuilder sb = new StringBuilder();
                for( int j = 0; j < lines.length; j++ ) {
                    sb.append( lines[ j ] ).append( '\n' );
                    if( j == i ) {
                        sb.append( PRAGMA ).append( '\n' );
                    }
                }
                return new Prepared( sb.toString(), i + 1, 1 );
            }
        }
        return new Prepared( "@startuml\n" + PRAGMA + "\n" + normalized + "\n@enduml\n", 0, 2 );
    }
}
