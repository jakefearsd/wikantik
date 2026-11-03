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
package com.wikantik.extractcli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.markdown.InlineTagSyntax;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 *  Records what a reader of each page would see, and compares two such records. The corpus check
 *  that runs before a comment-mask (or tag) change ships: a page whose reader-visible text
 *  <em>grows</em> is one the change newly exposes.
 *
 *  <pre>
 *  ReaderTextSnapshotCli snapshot &lt;pagesDir&gt; &lt;out.json&gt;
 *  ReaderTextSnapshotCli diff &lt;a.json&gt; &lt;b.json&gt;
 *  </pre>
 *
 *  <p>Exit codes: {@code 0} success (for {@code diff}: nothing grows), {@code 3} {@code diff} found a page that
 *  GROWS from a to b, {@code 1} usage error, {@code 2} I/O or other failure.</p>
 *
 *  <p>The reader's comment parse is looked up <b>by reflection</b>: {@code ReaderComments} where the
 *  build has it, otherwise {@code ObsidianComments}, which every earlier release has. That is what lets
 *  {@code bin/reader-text-diff.sh} compile this one source file against an old release's classpath and
 *  against the working tree's, and compare the two. Snapshot lines are per page:
 *  {@code stripped} (sha256 of the body with comments removed), {@code strippedLength},
 *  {@code comments} (the hidden ranges) and {@code tags}.</p>
 */
public final class ReaderTextSnapshotCli {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String[] COMMENT_CLASSES = {
        "com.wikantik.parser.markdown.ReaderComments", "com.wikantik.api.markdown.ObsidianComments" };

    /** Exit code of {@code diff} when a page GROWS; distinct from usage (1) and failure (2). */
    public static final int EXIT_GROWS = 3;

    private ReaderTextSnapshotCli() {
    }

    /** Writes the snapshot of every {@code *.md} directly in {@code pagesDir} to {@code out}. */
    public static void snapshot( final Path pagesDir, final Path out ) throws IOException {
        final Map< String, JsonObject > pages = new TreeMap<>();
        final List< Path > files;
        try ( Stream< Path > s = Files.list( pagesDir ) ) {
            files = s.filter( p -> p.getFileName().toString().endsWith( ".md" ) ).sorted().toList();
        }
        for ( final Path file : files ) {
            final String name = file.getFileName().toString();
            pages.put( name.substring( 0, name.length() - 3 ), describe( Files.readString( file, StandardCharsets.UTF_8 ) ) );
        }
        Files.writeString( out, GSON.toJson( pages ), StandardCharsets.UTF_8 );
    }

    private static JsonObject describe( final String text ) {
        final String body = FrontmatterParser.parse( text ).body();
        final List< int[] > ranges = commentRanges( body );
        final String stripped = strip( body );
        final JsonObject page = new JsonObject();
        page.addProperty( "stripped", sha256( stripped ) );
        page.addProperty( "strippedLength", stripped.length() );
        final JsonArray comments = new JsonArray();
        for ( final int[] r : ranges ) {
            final JsonArray pair = new JsonArray();
            pair.add( r[ 0 ] );
            pair.add( r[ 1 ] );
            comments.add( pair );
        }
        page.add( "comments", comments );
        final JsonArray tags = new JsonArray();
        new TreeSet<>( InlineTagSyntax.normalisedTags( body ) ).forEach( tags::add );
        page.add( "tags", tags );
        return page;
    }

    @SuppressWarnings( "unchecked" )
    private static List< int[] > commentRanges( final String body ) {
        return ( List< int[] > ) invokeComments( "ranges", body );
    }

    private static String strip( final String body ) {
        return ( String ) invokeComments( "strip", body );
    }

    private static Object invokeComments( final String method, final String body ) {
        for ( final String className : COMMENT_CLASSES ) {
            final Class< ? > type;
            try {
                type = Class.forName( className );
            } catch ( final ClassNotFoundException absent ) {
                continue;   // an older build: fall through to the next dialect
            }
            try {
                final Method m = type.getMethod( method, String.class );
                return m.invoke( null, body );
            } catch ( final NoSuchMethodException | IllegalAccessException | InvocationTargetException e ) {
                throw new IllegalStateException( className + "." + method + " is not callable", e );
            }
        }
        throw new IllegalStateException( "no comment parser on the classpath" );
    }

    private static String sha256( final String s ) {
        try {
            return HexFormat.of().formatHex( MessageDigest.getInstance( "SHA-256" ).digest( s.getBytes( StandardCharsets.UTF_8 ) ) );
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }

    /**
     *  Prints one line per changed page, {@code GROWS|SHRINKS|SAME-LENGTH page comments a->b tags +[..] -[..]},
     *  comparing {@code a} (before) to {@code b} (after).
     *
     *  @return {@link #EXIT_GROWS} when any page GROWS (the reader sees more text in {@code b}), else 0
     */
    public static int diff( final Path a, final Path b, final PrintStream out ) throws IOException {
        final JsonObject before = JsonParser.parseString( Files.readString( a, StandardCharsets.UTF_8 ) ).getAsJsonObject();
        final JsonObject after = JsonParser.parseString( Files.readString( b, StandardCharsets.UTF_8 ) ).getAsJsonObject();
        boolean grows = false;
        for ( final String name : new TreeSet<>( before.keySet() ) ) {
            if ( !after.has( name ) ) {
                out.println( "ONLY-IN-A " + name );
                continue;
            }
            grows |= comparePage( name, before.getAsJsonObject( name ), after.getAsJsonObject( name ), out );
        }
        after.keySet().stream().filter( n -> !before.has( n ) ).sorted().forEach( n -> out.println( "ONLY-IN-B " + n ) );
        return grows ? EXIT_GROWS : 0;
    }

    private static boolean comparePage( final String name, final JsonObject x, final JsonObject y, final PrintStream out ) {
        final List< String > tagsA = strings( x.getAsJsonArray( "tags" ) );
        final List< String > tagsB = strings( y.getAsJsonArray( "tags" ) );
        final int commentsA = x.getAsJsonArray( "comments" ).size();
        final int commentsB = y.getAsJsonArray( "comments" ).size();
        final boolean same = x.get( "stripped" ).equals( y.get( "stripped" ) );
        if ( same && commentsA == commentsB && tagsA.equals( tagsB ) ) {
            return false;
        }
        final int delta = y.get( "strippedLength" ).getAsInt() - x.get( "strippedLength" ).getAsInt();
        final String verdict = delta > 0 ? "GROWS" : delta < 0 ? "SHRINKS" : "SAME-LENGTH";
        final List< String > added = new ArrayList<>( tagsB );
        added.removeAll( tagsA );
        final List< String > removed = new ArrayList<>( tagsA );
        removed.removeAll( tagsB );
        out.println( verdict + " " + name + " comments " + commentsA + "→" + commentsB
                     + " tags +" + added + " -" + removed );
        return delta > 0;
    }

    private static List< String > strings( final JsonArray array ) {
        final List< String > values = new ArrayList<>();
        for ( final JsonElement e : array ) {
            values.add( e.getAsString() );
        }
        return values;
    }

    public static void main( final String[] args ) {
        System.exit( run( args ) );
    }

    static int run( final String[] args ) {
        try {
            if ( args.length == 3 && "snapshot".equals( args[ 0 ] ) ) {
                snapshot( Path.of( args[ 1 ] ), Path.of( args[ 2 ] ) );
                return 0;
            }
            if ( args.length == 3 && "diff".equals( args[ 0 ] ) ) {
                return diff( Path.of( args[ 1 ] ), Path.of( args[ 2 ] ), System.out );
            }
        } catch ( final IOException | RuntimeException e ) {
            System.err.println( "ReaderTextSnapshotCli failed: " + e );
            return 2;
        }
        System.err.println( "usage: ReaderTextSnapshotCli snapshot <pagesDir> <out.json> | diff <a.json> <b.json>\n"
                            + "exit: 0 ok / nothing grows, 3 diff found GROWS, 1 usage, 2 failure" );
        return 1;
    }
}
