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
package com.wikantik.collab.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PushResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 corpus, exported to {@code wikantik-frontend/src/collab/__fixtures__/collab-traces/} for
 * {@code collabTraceReplay.test.js}, which replays each client's entries through the real {@code @codemirror/collab}.
 * It holds, per file, only what that replay needs (profile, seed, initial document, per-client entries):
 * <ul>
 * <li>{@code <profile>-<seed>.json}: seeds 1..50 of every profile (120 steps), plus {@link #RESYNC_SEEDS} of LOSSY, which
 *     are known to reach a resync (rare: about 1 seed in 100);</li>
 * <li>{@code crafted-*.json}: SimClient driven directly through cases the one-pull-at-a-time protocol never produces
 *     (a foreign update ahead of own updates in one batch; a real gap);</li>
 * <li>{@code regression-<name>.json}: every committed regression trace, replayed through the scheduler.</li>
 * </ul>
 * Entries are trimmed: {@code push} (informational) is dropped and a {@code sendable} snapshot is kept only when it
 * differs from that client's previous one, and updates are stored once per file (see {@link #export}). Traces pin protocol messages, not the simulator's random-number draws.
 *
 * <p>The test regenerates the corpus and fails on any difference. Refresh with
 * {@code mvn -o test -pl wikantik-main -Dtest=CollabTraceCorpusDriftTest -Dcollab.traces.write=true}; write mode deletes
 * only generated file names.</p>
 */
class CollabTraceCorpusDriftTest {

    static final int SEEDS = 50;
    static final int STEPS = 120;
    /** LOSSY seeds (beyond 1..50) whose 120-step run contains a resync; asserted below so a generator change is noticed. */
    static final long[] RESYNC_SEEDS = { 74, 185, 187, 618, 688, 884, 1127, 1204, 1270, 1301, 1394, 1468 };
    static final Path DIR = Path.of( "..", "wikantik-frontend", "src", "collab", "__fixtures__", "collab-traces" );
    static final Path REGRESSIONS = Path.of( "src", "test", "resources", "collab", "regressions" );
    private static final Pattern GENERATED =
        Pattern.compile( "(calm|lossy|agent_heavy|publish_storm)-\\d+\\.json|crafted-.*\\.json|regression-.*\\.json" );
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static String file( final SimProfile profile, final long seed ) {
        return profile.name().toLowerCase() + "-" + seed + ".json";
    }

    private static SimTrace run( final SimProfile profile, final long seed ) {
        return new SimScheduler( seed, profile, SimScheduler.STUB, 2 + (int) ( seed % 3 ) ).run( STEPS );
    }

    static Map< String, String > generate() throws IOException {
        final Map< String, String > out = new TreeMap<>();
        for( final SimProfile profile : SimProfile.values() ) {
            for( long seed = 1; seed <= SEEDS; seed++ ) {
                final SimTrace t = run( profile, seed );
                out.put( file( profile, seed ), export( t.profile().name(), t.seed(), t.initialDoc(), t.clientTraces() ) );
            }
        }
        for( final long seed : RESYNC_SEEDS ) {
            final SimTrace t = run( SimProfile.LOSSY, seed );
            assertTrue( t.clientTraces().stream().anyMatch( l -> l.stream().anyMatch( e -> e.contains( "\"type\":\"resync\"" ) ) ),
                "LOSSY seed " + seed + " no longer reaches a resync; re-probe RESYNC_SEEDS" );
            out.put( file( SimProfile.LOSSY, seed ), export( t.profile().name(), t.seed(), t.initialDoc(), t.clientTraces() ) );
        }
        out.put( "crafted-foreign-then-own.json", craftedForeignThenOwn() );
        out.put( "crafted-gap.json", craftedGap() );
        if( Files.isDirectory( REGRESSIONS ) ) {
            try( var files = Files.list( REGRESSIONS ) ) {
                for( final Path p : files.filter( f -> f.toString().endsWith( ".json" ) ).sorted().toList() ) {
                    final SimTrace recorded = SimTrace.fromJson( Files.readString( p, StandardCharsets.UTF_8 ) );
                    final SimTrace t = SimScheduler.replay( recorded, SimScheduler.STUB );
                    final String name = p.getFileName().toString();
                    out.put( "regression-" + name, export( t.profile().name(), t.seed(), t.initialDoc(), t.clientTraces() ) );
                }
            }
        }
        return out;
    }

    /** FNV-1a over the UTF-16 code units, as {@code <length>-<8 hex digits>}; the JS replay computes the same. */
    static String docHash( final String doc ) {
        return textHash( doc );
    }

    /** Canonical text of a JSON value of arrays, integers and strings (all a ChangeSet or update list contains). */
    static String canon( final JsonElement e ) {
        if( e.isJsonArray() ) {
            final StringBuilder sb = new StringBuilder( "[" );
            e.getAsJsonArray().forEach( x -> sb.append( canon( x ) ).append( ',' ) );
            return sb.append( ']' ).toString();
        }
        if( e.isJsonObject() ) {
            final JsonObject o = e.getAsJsonObject();
            return "{" + canon( o.get( "clientID" ) ) + "|" + canon( o.get( "changes" ) ) + "}";
        }
        final var prim = e.getAsJsonPrimitive();
        return prim.isString() ? prim.getAsString().length() + ":" + prim.getAsString() : prim.getAsString();
    }

    static String textHash( final String doc ) {
        int h = 0x811c9dc5;
        for( int i = 0; i < doc.length(); i++ ) {
            h = ( h ^ doc.charAt( i ) ) * 0x01000193;
        }
        return doc.length() + "-" + String.format( "%08x", h );
    }

    /**
     * The compact file form. Every distinct update ({@code clientID} + {@code changes}) is stored once in a per-file
     * {@code updates} table and referenced by index from {@code receive.updates}; a
     * {@code sendable} snapshot carries {@code dh} (doc hash) and {@code sh} (sendable hash) (FNV-1a of
     * {@link #canon}, which the replay recomputes from the library's own updates) instead of the text and the lists ({@code join} and {@code resync} keep the
     * full document, which re-seeds the replay). The snapshot after a {@code local} is folded into that
     * {@code local} entry as {@code version}, {@code dh} (doc hash) and {@code sn} (sendable count) (a local edit just appends to the
     * unconfirmed list); the rebased lists after a {@code receive} or {@code resync} are checked by hash.
     */
    static String export( final String profile, final long seed, final String initialDoc, final List< List< String > > clientTraces ) {
        final JsonObject root = new JsonObject();
        root.addProperty( "profile", profile );
        root.addProperty( "seed", seed );
        root.addProperty( "initialDoc", initialDoc );
        final JsonArray table = new JsonArray();
        final Map< String, Integer > index = new java.util.HashMap<>();
        final JsonArray clients = new JsonArray();
        for( final List< String > entries : clientTraces ) {
            final JsonArray arr = new JsonArray();
            JsonElement previous = null;
            String last = "";
            JsonObject lastObject = null;
            for( final String raw : entries ) {
                final JsonObject e = JsonParser.parseString( raw ).getAsJsonObject();
                final String type = e.get( "type" ).getAsString();
                if( type.equals( "push" ) ) {
                    continue;
                }
                final String after = last;
                last = type;
                if( type.equals( "sendable" ) ) {
                    if( e.equals( previous ) ) {
                        continue;
                    }
                    previous = e.deepCopy();
                    final String hash = docHash( e.remove( "doc" ).getAsString() );
                    if( after.equals( "local" ) && lastObject != null ) {
                        // a local edit only appends to the unconfirmed list: its count and the document are the check,
                        // carried on the local entry itself
                        lastObject.addProperty( "version", e.get( "version" ).getAsInt() );
                        lastObject.addProperty( "dh", hash );
                        lastObject.addProperty( "sn", e.get( "sendable" ).getAsJsonArray().size() );
                        continue;
                    }
                    final JsonArray list = e.remove( "sendable" ).getAsJsonArray();
                    e.addProperty( "dh", hash );
                    e.addProperty( "sh", textHash( canon( list ) ) );
                } else {
                    if( type.equals( "resync" ) || type.equals( "join" ) ) {
                        previous = null;
                    }
                    if( type.equals( "receive" ) ) {
                        e.add( "updates", refs( e.get( "updates" ).getAsJsonArray(), table, index ) );
                    }
                }
                lastObject = e;
                arr.add( e );
            }
            clients.add( arr );
        }
        root.add( "updates", table );
        root.add( "clients", clients );
        return SimTrace.escapeLoneSurrogates( GSON.toJson( root ) ) + "\n";
    }

    private static JsonArray refs( final JsonArray updates, final JsonArray table, final Map< String, Integer > index ) {
        final JsonArray out = new JsonArray();
        for( final JsonElement u : updates ) {
            final Integer found = index.get( u.toString() );
            if( found != null ) {
                out.add( found );
            } else {
                index.put( u.toString(), table.size() );
                out.add( table.size() );
                table.add( u );
            }
        }
        return out;
    }

    /**
     * A batch [foreign F, own A', own B'] reaching a client with unconfirmed [A, B], all inserting at the same position.
     * The library maps F over the client's own originals with {@code before=true}, so F lands before A and B.
     */
    static String craftedForeignThenOwn() {
        final SimClient a = new SimClient( "a", new Joined( "p-a", "ca", 0, "ab" ) );
        final ChangeSet ca = ChangeSet.replace( 2, 1, 1, "A" );
        a.localEdit( ca );                                          // "aAb"
        final ChangeSet cb = ChangeSet.replace( 3, 2, 2, "B" );
        a.localEdit( cb );                                          // "aABb"
        a.onPushResult( new PushResult( 200, 2, null ) );
        final ChangeSet f = ChangeSet.replace( 2, 1, 1, "F" );      // foreign, based on version 0
        final ChangeSet aServer = ca.map( f );                      // server order: F, then A, then B
        final ChangeSet bServer = cb.map( f.map( ca, true ) );
        a.receive( List.of( new Update( "cf", f ), new Update( "ca", aServer ), new Update( "ca", bServer ) ), 0 );
        assertEquals( "aFABb", a.doc() );
        assertTrue( a.sendable().isEmpty() );
        a.localEdit( ChangeSet.replace( 5, 5, 5, "Z" ) );
        assertEquals( "aFABbZ", a.doc() );
        return export( "CRAFTED", 0, "ab", List.of( a.trace() ) );
    }

    /** A pull batch starting beyond the client's version is a gap and is not applied; a contiguous one afterwards is. */
    static String craftedGap() {
        final SimClient a = new SimClient( "a", new Joined( "p-a", "ca", 0, "abc" ) );
        a.localEdit( ChangeSet.replace( 3, 3, 3, "X" ) );
        final Update foreign = new Update( "cb", ChangeSet.replace( 3, 0, 0, "R" ) );
        a.receive( List.of( new Update( "cb", ChangeSet.replace( 3, 1, 1, "Q" ) ) ), 5 );
        assertEquals( "abcX", a.doc() );
        assertEquals( 0, a.version() );
        a.receive( List.of( foreign ), 0 );
        assertEquals( "RabcX", a.doc() );
        return export( "CRAFTED", 1, "abc", List.of( a.trace() ) );
    }

    @Test
    void corpusIsCurrent() throws IOException {
        final Map< String, String > expected = generate();
        assertTrue( expected.size() >= 4 * SEEDS + RESYNC_SEEDS.length + 2, "corpus size " + expected.size() );
        if( Boolean.getBoolean( "collab.traces.write" ) ) {
            Files.createDirectories( DIR );
            try( var existing = Files.list( DIR ) ) {
                for( final Path p : existing.filter( f -> GENERATED.matcher( f.getFileName().toString() ).matches() ).toList() ) {
                    Files.delete( p );
                }
            }
            for( final Map.Entry< String, String > e : expected.entrySet() ) {
                Files.writeString( DIR.resolve( e.getKey() ), e.getValue(), StandardCharsets.UTF_8 );
            }
            return;
        }
        assertTrue( Files.isDirectory( DIR ), "corpus missing; regenerate with -Dcollab.traces.write=true" );
        final List< String > problems = new ArrayList<>();
        for( final Map.Entry< String, String > e : expected.entrySet() ) {
            final Path p = DIR.resolve( e.getKey() );
            if( !Files.exists( p ) ) {
                problems.add( "missing " + e.getKey() );
            } else if( !Files.readString( p, StandardCharsets.UTF_8 ).equals( e.getValue() ) ) {
                problems.add( "differs " + e.getKey() );
            }
        }
        try( var existing = Files.list( DIR ) ) {
            existing.map( f -> f.getFileName().toString() )
                .filter( n -> GENERATED.matcher( n ).matches() && !expected.containsKey( n ) )
                .forEach( n -> problems.add( "stale " + n ) );
        }
        assertTrue( problems.isEmpty(), "trace corpus is stale (" + problems.size() + "): "
            + problems.subList( 0, Math.min( 10, problems.size() ) )
            + "; regenerate with -Dcollab.traces.write=true" );
    }
}
