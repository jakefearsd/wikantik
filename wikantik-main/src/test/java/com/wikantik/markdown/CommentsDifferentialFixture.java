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
package com.wikantik.markdown;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wikantik.parser.markdown.ReaderComments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The seeded generator behind {@code comments-differential.json}, the corpus both the server ({@code CommentsDifferentialTest})
 * and the editor ({@code obsidianComments.differential.test.js}) must agree on. Inputs are random concatenations of the
 * constructs that interact with {@code %%} (code, math, tables, lists, wikilinks, HTML); the expected ranges are
 * {@link ReaderComments#ranges}, which the render oracle in the test checks against the real rendered page.
 *
 * <p>Refresh (no exec plugin is configured, so this goes through the test):
 * {@code mvn -o -q test -pl wikantik-main -Dtest=CommentsDifferentialTest#fixtureIsCurrent -Dcomments.differential.write=true}
 */
public final class CommentsDifferentialFixture {

    static final long SEED = 127L;
    static final int COUNT = 2000;
    static final String GENERATOR = "java.util.Random(127): 4-17 tokens per document from a fixed list of 35, kept only if it contains %%";
    static final Path FILE = Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "comments-differential.json" );

    private static final String[] TOKENS = {
        "%%", "%%", "%%", "`", "`", "$", "$$\n", "|", " | ", "\n", "\n\n", "- ", "1. ", "> ", "    ", "  ", "```\n", "[[", "]]",
        "<b t=\"", "\">", "\\", "a", "b ", " ", "|---|---|\n", "#t ", "==", "x\n", "$x$",
        "| a | b |\n", "|---|---|\n", "| `x | y` |\n", "| $a | b$ |\n" };

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CommentsDifferentialFixture() {
    }

    /** @return {@link #COUNT} generated documents, in generation order */
    static List< String > inputs() {
        final Random random = new Random( SEED );
        final List< String > kept = new ArrayList<>();
        while ( kept.size() < COUNT ) {
            final int n = 4 + random.nextInt( 14 );
            final StringBuilder sb = new StringBuilder();
            for ( int i = 0; i < n; i++ ) {
                sb.append( TOKENS[ random.nextInt( TOKENS.length ) ] );
            }
            if ( sb.indexOf( "%%" ) >= 0 ) {
                kept.add( sb.toString() );
            }
        }
        return kept;
    }

    /** @return the fixture file's exact text, one case per line, so a diff names the case */
    static String render( final List< String > allowEntries ) {
        final List< String > inputs = inputs();
        final java.util.Set< String > known = new java.util.HashSet<>( inputs );
        final List< String > kept = allowEntries.stream()
                .filter( e -> known.contains( com.google.gson.JsonParser.parseString( e ).getAsJsonObject().get( "input" ).getAsString() ) ).toList();
        return render( kept, inputs );
    }

    private static String render( final List< String > allowEntries, final List< String > inputs ) {
        final StringBuilder out = new StringBuilder();
        out.append( "{\n \"seed\": " ).append( SEED ).append( ",\n \"count\": " ).append( COUNT )
           .append( ",\n \"generator\": " ).append( GSON.toJson( GENERATOR ) )
           .append( ",\n \"allow\": [" );
        for ( int i = 0; i < allowEntries.size(); i++ ) {
            out.append( i == 0 ? "\n  " : ",\n  " ).append( allowEntries.get( i ) );
        }
        out.append( allowEntries.isEmpty() ? "],\n \"cases\": [\n" : "\n ],\n \"cases\": [\n" );
        for ( int i = 0; i < inputs.size(); i++ ) {
            final String input = inputs.get( i );
            final List< int[] > ranges = ReaderComments.ranges( input );
            out.append( "  {\"input\": " ).append( GSON.toJson( input ) ).append( ", \"ranges\": " ).append( GSON.toJson( ranges ) )
               .append( ", \"rangesCrlf\": " ).append( GSON.toJson( ReaderComments.ranges( input.replace( "\n", "\r\n" ) ) ) ).append( '}' )
               .append( i + 1 < inputs.size() ? ",\n" : "\n" );
        }
        return out.append( " ]\n}\n" ).toString();
    }

    /** Writes the fixture, keeping the {@code allow} entries already in the file whose input is still a case. */
    public static void main( final String[] args ) throws IOException {
        Files.writeString( FILE, render( CommentsDifferentialTest.currentAllow() ), StandardCharsets.UTF_8 );
    }
}
