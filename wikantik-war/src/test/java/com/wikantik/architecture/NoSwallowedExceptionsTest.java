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
package com.wikantik.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project rule (CLAUDE.md): never swallow an exception. A catch in main code must log or rethrow;
 * a body that is empty, comment-only, or only {@code return …;} / {@code continue;} / {@code break;} fails.
 */
class NoSwallowedExceptionsTest {

    private static final Pattern CATCH = Pattern.compile( "\\bcatch\\s*\\([^)]*\\)\\s*\\{" );
    private static final Pattern SILENT = Pattern.compile( "(return\\b[^;]*;|continue\\s*;|break\\s*;)?" );
    private static final Set< String > SKIP_DIRS = Set.of( "target", "node_modules", ".git", ".worktrees", ".claude", "tomcat" );

    @Test
    void scannerFlagsEmptyCommentOnlyAndSilentBodies() {
        final String src = String.join( "\n",
            "class A {",
            "  void a() { try { x(); } catch( final Exception e ) { } }",
            "  void b() { try { x(); } catch( final Exception e ) { // expected",
            "  } }",
            "  int c() { try { return x(); } catch( final NumberFormatException e ) { return -1; } }",
            "  void d() { for(;;) { try { x(); } catch( final Exception e ) { continue; } } }",
            "  void e() { try { x(); } catch( final Exception e ) { LOG.debug( \"why\", e ); } }",
            "  void f() { try { x(); } catch( final Exception e ) { throw new IllegalStateException( e ); } }",
            "  void g() { try { x(); } catch( final InterruptedException e ) { Thread.currentThread().interrupt(); } }",
            "  void h() { String s = \"catch( x ) { }\"; }",
            "}" );
        assertEquals( List.of( 2, 3, 5, 6 ), scanSource( src ) );
    }

    @Test
    void mainCodeNeverSwallowsAnException() throws IOException {
        final Path root = ConfigSurfaceDriftTest.repoRoot();
        final List< String > violations = new ArrayList<>();
        Files.walkFileTree( root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory( final Path dir, final BasicFileAttributes attrs ) {
                return SKIP_DIRS.contains( dir.getFileName().toString() ) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile( final Path file, final BasicFileAttributes attrs ) {
                final String rel = root.relativize( file ).toString().replace( '\\', '/' );
                if( rel.endsWith( ".java" ) && rel.contains( "/src/main/java/" ) ) {
                    try {
                        for( final int line : scanSource( Files.readString( file ) ) ) {
                            violations.add( rel + ":" + line );
                        }
                    } catch( final IOException e ) {
                        throw new UncheckedIOException( e );
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        } );
        assertTrue( violations.isEmpty(),
            "catch blocks that swallow the exception (log with context, or rethrow):\n  " + String.join( "\n  ", violations ) );
    }

    /** Returns the 1-based line of every catch whose body neither logs nor does anything but return/continue/break. */
    static List< Integer > scanSource( final String source ) {
        final String code = blankStringsAndComments( source );
        final List< Integer > hits = new ArrayList<>();
        final Matcher m = CATCH.matcher( code );
        while( m.find() ) {
            int depth = 1;
            int i = m.end();
            while( i < code.length() && depth > 0 ) {
                final char c = code.charAt( i++ );
                if( c == '{' ) depth++;
                else if( c == '}' ) depth--;
            }
            final String body = code.substring( m.end(), Math.max( m.end(), i - 1 ) ).trim().replaceAll( "\\s+", " " );
            if( SILENT.matcher( body ).matches() ) {
                hits.add( lineOf( source, m.start() ) );
            }
        }
        return hits;
    }

    /** Replaces comment and string/char-literal contents with spaces, preserving offsets and newlines. */
    private static String blankStringsAndComments( final String s ) {
        final StringBuilder out = new StringBuilder( s );
        int i = 0;
        while( i < s.length() ) {
            final char c = s.charAt( i );
            if( c == '/' && i + 1 < s.length() && s.charAt( i + 1 ) == '/' ) {
                while( i < s.length() && s.charAt( i ) != '\n' ) out.setCharAt( i++, ' ' );
            } else if( c == '/' && i + 1 < s.length() && s.charAt( i + 1 ) == '*' ) {
                final int end = s.indexOf( "*/", i + 2 );
                final int stop = end < 0 ? s.length() : end + 2;
                for( ; i < stop; i++ ) if( s.charAt( i ) != '\n' ) out.setCharAt( i, ' ' );
            } else if( c == '"' && s.startsWith( "\"\"\"", i ) ) {
                final int end = s.indexOf( "\"\"\"", i + 3 );
                final int stop = end < 0 ? s.length() : end + 3;
                for( ; i < stop; i++ ) if( s.charAt( i ) != '\n' ) out.setCharAt( i, ' ' );
            } else if( c == '"' || c == '\'' ) {
                int j = i + 1;
                while( j < s.length() && s.charAt( j ) != c && s.charAt( j ) != '\n' ) j += s.charAt( j ) == '\\' ? 2 : 1;
                for( int k = i; k <= Math.min( j, s.length() - 1 ); k++ ) out.setCharAt( k, ' ' );
                i = j + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static int lineOf( final String s, final int offset ) {
        int line = 1;
        for( int i = 0; i < offset; i++ ) if( s.charAt( i ) == '\n' ) line++;
        return line;
    }
}
