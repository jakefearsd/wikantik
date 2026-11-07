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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 corpus: 50 seeds x 4 profiles of {@link SimScheduler} runs, exported to
 * {@code wikantik-frontend/src/collab/__fixtures__/collab-traces/<profile>-<seed>.json} for
 * {@code collabTraceReplay.test.js}, which replays each client's entries through the real {@code @codemirror/collab}.
 * The files hold only what that replay needs (profile, seed, initial document, per-client entries without the informational {@code push} ones); they pin protocol
 * messages, not the simulator's random-number draws, so a change to the scheduler's generator regenerates them without
 * touching the replay.
 *
 * <p>The test regenerates the corpus and fails on any difference. Refresh with
 * {@code mvn -o test -pl wikantik-main -Dtest=CollabTraceCorpusDriftTest -Dcollab.traces.write=true}.</p>
 */
class CollabTraceCorpusDriftTest {

    static final int SEEDS = 50;
    static final int STEPS = 120;
    static final Path DIR = Path.of( "..", "wikantik-frontend", "src", "collab", "__fixtures__", "collab-traces" );
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static String file( final SimProfile profile, final long seed ) {
        return profile.name().toLowerCase() + "-" + seed + ".json";
    }

    static Map< String, String > generate() {
        final Map< String, String > out = new TreeMap<>();
        for( final SimProfile profile : SimProfile.values() ) {
            for( long seed = 1; seed <= SEEDS; seed++ ) {
                final int clients = 2 + (int) ( seed % 3 );
                final SimTrace t = new SimScheduler( seed, profile, SimScheduler.STUB, clients ).run( STEPS );
                out.put( file( profile, seed ), export( t ) );
            }
        }
        return out;
    }

    static String export( final SimTrace t ) {
        final JsonObject root = new JsonObject();
        root.addProperty( "profile", t.profile().name() );
        root.addProperty( "seed", t.seed() );
        root.addProperty( "initialDoc", t.initialDoc() );
        final JsonArray clients = new JsonArray();
        for( final List< String > entries : t.clientTraces() ) {
            final JsonArray arr = new JsonArray();
            entries.stream().map( JsonParser::parseString )
                .filter( e -> !"push".equals( e.getAsJsonObject().get( "type" ).getAsString() ) ).forEach( arr::add );
            clients.add( arr );
        }
        root.add( "clients", clients );
        return SimTrace.escapeLoneSurrogates( GSON.toJson( root ) ) + "\n";
    }

    @Test
    void corpusIsCurrent() throws IOException {
        final Map< String, String > expected = generate();
        assertEquals( 4 * SEEDS, expected.size() );
        if( Boolean.getBoolean( "collab.traces.write" ) ) {
            Files.createDirectories( DIR );
            try( var existing = Files.list( DIR ) ) {
                for( final Path p : existing.filter( f -> f.toString().endsWith( ".json" ) ).toList() ) {
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
            existing.map( f -> f.getFileName().toString() ).filter( n -> n.endsWith( ".json" ) && !expected.containsKey( n ) )
                .forEach( n -> problems.add( "stale " + n ) );
        }
        assertTrue( problems.isEmpty(), "trace corpus is stale (" + problems.size() + "): "
            + problems.subList( 0, Math.min( 10, problems.size() ) )
            + "; regenerate with -Dcollab.traces.write=true" );
    }
}
