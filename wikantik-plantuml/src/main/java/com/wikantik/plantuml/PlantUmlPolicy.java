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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, fail-closed policy over PlantUML author source. It runs before any PlantUML class is touched and
 * refuses anything that could read files, the environment or the network, load images, change the layout
 * engine, or link somewhere unsafe. Checks are line-oriented, case-insensitive and ordered; the first match wins.
 */
public final class PlantUmlPolicy {

    private static final Logger LOG = LogManager.getLogger( PlantUmlPolicy.class );

    /** Maximum source size in UTF-8 bytes. A constant (not a config key) because callers are static. */
    public static final int MAX_SOURCE_BYTES = 65_536;

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final Pattern START_ANY = Pattern.compile( "^\\s*(@start\\w+)", FLAGS );
    /** The exact token {@code @startuml}, as PlantUML allows {@code @startuml(id=..)} and {@code @startuml name}. */
    private static final Pattern START_UML = Pattern.compile( "^\\s*@startuml(\\s|\\(|$)", FLAGS );
    private static final Pattern DITAA = Pattern.compile( "^\\s*ditaa\\b", FLAGS );
    // PlantUML renders these as non-UML Help/Crash diagrams.
    private static final Pattern HELP_OR_CRASH = Pattern.compile( "^\\s*(help\\b|crash\\s*$)", FLAGS );

    /**
     * An embedded sub-diagram ({@code {{ … }}}, in a note or a label) is laid out on its own: it does not inherit the
     * forced Smetana pragma (so PlantUML reaches for the Graphviz binary) nor the {@code @startuml}-only, help and
     * crash checks. Any line holding two consecutive opening braces is refused.
     */
    private static final String EMBEDDED = "{{";
    private static final Pattern INCLUDE = Pattern.compile( "(?:^|\\s)!(?:include\\w*|import)\\b", FLAGS );
    /** The bundled C4 standard library is the only include allowed: it is a fixed classpath resource. */
    private static final Pattern ALLOWED_INCLUDE = Pattern.compile( "^\\s*!include\\s+<C4/[\\w/]+>\\s*$", FLAGS );
    private static final Pattern THEME_FROM = Pattern.compile( "^\\s*!theme\\b[^\\n]*\\bfrom\\b", FLAGS );
    private static final Pattern LAYOUT_PRAGMA = Pattern.compile( "(?:^|\\s)!pragma\\s+(?:layout|graphviz_dot)\\b", FLAGS );

    /**
     * Builtins of PlantUML 1.2026.8 ({@code net.sourceforge.plantuml.tim.builtin}) that touch the file system,
     * environment or classpath, plus the indirect callers that could reach them by a computed name. Longer names first so that
     * {@code filename_no_extension} is not cut short by {@code filename}.
     */
    static final List< String > DENIED_BUILTINS = List.of( "filename_no_extension", "file_exists", "get_all_stdlib",
        "get_all_theme", "get_stdlib", "load_json", "filedate", "dirpath", "filename", "getenv",
        // Indirect callers: a function name built at run time ("%fi"+"le_exists") would evade the literal deny-list.
        "call_user_func", "invoke_procedure", "eval" );
    private static final Pattern FILE_ACCESS = Pattern.compile(
        "%(?:" + String.join( "|", DENIED_BUILTINS ) + ")(?!\\w)|(?:^|\\s)!dump_memory\\b", FLAGS );

    private static final Pattern IMAGE = Pattern.compile( "<img\\b", FLAGS );
    private static final Pattern LINK = Pattern.compile( "\\[\\[\\s*([^\\s{\\]]*)" );

    /**
     * Result of analysing a source.
     *
     * @param reason      refusal reason code, or {@code null} when the source is allowed
     * @param linkTargets link targets ({@code [[target ...]]}) found in the source, in order
     */
    public record Analysis( String reason, List< String > linkTargets ) {

        public Analysis {
            linkTargets = List.copyOf( linkTargets );
        }

        /** @return true when the source passed the policy */
        public boolean allowed() {
            return reason == null;
        }
    }

    private PlantUmlPolicy() {
    }

    /**
     * Applies the policy. Never throws.
     *
     * @param source the author's diagram source
     * @return the verdict plus the link targets found
     */
    public static Analysis analyse( final String source ) {
        try {
            return doAnalyse( source == null ? "" : source );
        } catch( final RuntimeException | StackOverflowError e ) {
            LOG.warn( "PlantUML policy analysis failed unexpectedly; refusing the source: {}", e.toString(), e );
            return new Analysis( "render-failed", List.of() );
        }
    }

    private static Analysis doAnalyse( final String source ) {
        if( source.getBytes( StandardCharsets.UTF_8 ).length > MAX_SOURCE_BYTES ) {
            return refused( "too-large" );
        }
        final List< String > lines = source.replace( "\r\n", "\n" ).replace( '\r', '\n' ).lines().toList();
        final String reason = firstReason( lines );
        if( reason != null ) {
            return refused( reason );
        }
        return new Analysis( null, linkTargets( lines ) );
    }

    private static String firstReason( final List< String > lines ) {
        String reason = diagramKindReason( lines );
        if( reason == null ) {
            reason = lineReason( lines, l -> l.contains( EMBEDDED ), "embedded-diagram" );
        }
        if( reason == null ) {
            reason = lineReason( lines, PlantUmlPolicy::isForbiddenInclude, "include" );
        }
        if( reason == null ) {
            reason = lineReason( lines, l -> LAYOUT_PRAGMA.matcher( l ).find(), "layout-pragma" );
        }
        if( reason == null ) {
            reason = lineReason( lines, l -> FILE_ACCESS.matcher( l ).find(), "file-access" );
        }
        if( reason == null ) {
            reason = lineReason( lines, l -> IMAGE.matcher( l ).find(), "image" );
        }
        if( reason == null ) {
            reason = lineReason( lines, PlantUmlPolicy::hasUnsafeLink, "unsafe-link" );
        }
        return reason;
    }

    private static String diagramKindReason( final List< String > lines ) {
        int starts = 0;
        boolean sawFirstStart = false;
        for( final String line : lines ) {
            final Matcher any = START_ANY.matcher( line );
            if( any.find() && !sawFirstStart ) {
                sawFirstStart = true;
                if( !"@startuml".equalsIgnoreCase( any.group( 1 ) ) || !START_UML.matcher( line ).find() ) {
                    return "unsupported-diagram";
                }
            }
            if( START_UML.matcher( line ).find() ) {
                starts++;
            }
            if( DITAA.matcher( line ).find() || HELP_OR_CRASH.matcher( line ).find() ) {
                return "unsupported-diagram";
            }
        }
        return starts > 1 ? "multiple-diagrams" : null;
    }

    private static String lineReason( final List< String > lines, final Predicate< String > test,
                                      final String reason ) {
        return lines.stream().anyMatch( test ) ? reason : null;
    }

    private static boolean isForbiddenInclude( final String line ) {
        if( THEME_FROM.matcher( line ).find() ) {
            return true;
        }
        return INCLUDE.matcher( line ).find() && !ALLOWED_INCLUDE.matcher( line ).matches();
    }

    private static boolean hasUnsafeLink( final String line ) {
        final Matcher m = LINK.matcher( line );
        while( m.find() ) {
            if( !m.group( 1 ).isEmpty() && !isSafeTarget( m.group( 1 ) ) ) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeTarget( final String target ) {
        final String t = target.toLowerCase( Locale.ROOT );
        return t.startsWith( "/wiki/" ) || t.startsWith( "#" ) || t.startsWith( "http://" ) || t.startsWith( "https://" );
    }

    private static List< String > linkTargets( final List< String > lines ) {
        final List< String > targets = new ArrayList<>();
        for( final String line : lines ) {
            final Matcher m = LINK.matcher( line );
            while( m.find() ) {
                if( !m.group( 1 ).isEmpty() ) {
                    targets.add( m.group( 1 ) );
                }
            }
        }
        return targets;
    }

    private static Analysis refused( final String reason ) {
        return new Analysis( reason, List.of() );
    }
}
