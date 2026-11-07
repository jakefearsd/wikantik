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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.collab.MetaJson;
import com.wikantik.collab.model.MetaOp;
import com.wikantik.collab.testing.SimClient.MetaState;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds {@link MetaState}, the simulator's metadata model, to {@code collabMeta.js} (Task 10): replays the shared
 * {@code wikantik-frontend/src/collab/__fixtures__/collab-meta-cases.json}, which {@code collabMeta.cases.test.js}
 * derives from the JS implementation, and asserts the displayed metadata and the pending count after every step.
 *
 * <p>Step vocabulary (language-neutral): {@code edit {field,value} | add {field,item} | remove {field,item} | take |
 * fail | ack {metaSeq} | remote {ops,metaSeq}}. {@code take} marks every unsent edit in flight; {@code fail} returns
 * every in-flight one to unsent; {@code ack} confirms the edits taken last.</p>
 */
class CollabMetaCasesTest {

    static final Path FIXTURE = Path.of( "..", "wikantik-frontend", "src", "collab", "__fixtures__", "collab-meta-cases.json" );
    private static final Gson GSON = MetaJson.GSON;

    private static JsonArray cases() throws IOException {
        return JsonParser.parseString( Files.readString( FIXTURE, StandardCharsets.UTF_8 ) ).getAsJsonArray();
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > map( final JsonElement e ) {
        return GSON.fromJson( e, Map.class );
    }

    private static Object value( final JsonElement e ) {
        return e == null || e.isJsonNull() ? null : GSON.fromJson( e, Object.class );
    }

    @Test
    void theFixtureIsThereAndCoversEveryStep() throws IOException {
        final StringBuilder all = new StringBuilder();
        cases().forEach( c -> c.getAsJsonObject().getAsJsonArray( "steps" )
            .forEach( s -> all.append( s.getAsJsonObject().get( "do" ).getAsString() ).append( ' ' ) ) );
        for( final String step : List.of( "edit", "add", "remove", "take", "fail", "ack", "remote" ) ) {
            assertTrue( all.toString().contains( step + " " ), "the fixture never uses step " + step );
        }
        assertTrue( cases().size() >= 7, "only " + cases().size() + " cases" );
    }

    @TestFactory
    Stream< DynamicTest > everyCaseReplaysAsInJavaScript() throws IOException {
        final List< DynamicTest > out = new ArrayList<>();
        for( final JsonElement e : cases() ) {
            final JsonObject c = e.getAsJsonObject();
            out.add( DynamicTest.dynamicTest( c.get( "name" ).getAsString(), () -> replay( c ) ) );
        }
        return out.stream();
    }

    private static void replay( final JsonObject c ) {
        final MetaState m = new MetaState( map( c.get( "meta" ) ), c.get( "metaSeq" ).getAsLong() );
        List< MetaState.Entry > taken = List.of();
        int i = 0;
        for( final JsonElement se : c.getAsJsonArray( "steps" ) ) {
            final JsonObject s = se.getAsJsonObject();
            final String what = s.get( "do" ).getAsString();
            switch( what ) {
                case "edit" -> m.edit( s.get( "field" ).getAsString(), value( s.get( "value" ) ) );
                case "add" -> m.add( s.get( "field" ).getAsString(), s.get( "item" ).getAsString() );
                case "remove" -> m.remove( s.get( "field" ).getAsString(), s.get( "item" ).getAsString() );
                case "take" -> taken = m.takeSendable();
                case "fail" -> m.failed( null );
                case "ack" -> m.acked( taken, s.get( "metaSeq" ).getAsLong() );
                case "remote" -> {
                    final List< MetaOp > ops = new ArrayList<>();
                    for( final JsonElement oe : s.getAsJsonArray( "ops" ) ) {
                        final JsonObject o = oe.getAsJsonObject();
                        ops.add( new MetaOp( o.get( "field" ).getAsString(), value( o.get( "value" ) ),
                            o.has( "by" ) ? o.get( "by" ).getAsString() : null ) );
                    }
                    m.applyRemote( ops, s.has( "metaSeq" ) ? s.get( "metaSeq" ).getAsLong() : null );
                }
                default -> throw new IllegalArgumentException( "unknown step " + what );
            }
            final String at = c.get( "name" ).getAsString() + " step " + i + " (" + what + ")";
            assertEquals( s.get( "view" ), GSON.toJsonTree( m.view() ), at + " view" );
            assertEquals( s.get( "pending" ).getAsInt(), m.pending().size(), at + " pending" );
            i++;
        }
    }

    @Test
    void theHeadlineRulesHoldByHand() {
        // a pending ADD survives the gap between its ack and the pull that reaches the acked seq
        final MetaState m = new MetaState( Map.of( "tags", List.of( "a" ) ), 3 );
        m.add( "tags", "x" );
        final List< MetaState.Entry > taken = m.takeSendable();
        m.acked( taken, 4L );
        m.applyRemote( List.of(), 3L );
        assertEquals( List.of( "a", "x" ), m.view().get( "tags" ) );
        assertEquals( 1, m.pending().size() );
        m.applyRemote( List.of( new MetaOp( "tags", List.of( "a", "x" ) ) ), 4L );
        assertEquals( 0, m.pending().size() );
        // unsent SETs of one field coalesce; null removes the field
        final MetaState s = new MetaState( Map.of( "summary", "s" ), 0 );
        s.edit( "summary", "a" );
        s.edit( "summary", null );
        assertEquals( 1, s.pending().size() );
        assertTrue( !s.view().containsKey( "summary" ) );
        // a scalar is a one-item list; removing the last item leaves an empty list
        final MetaState t = new MetaState( Map.of( "tags", "solo" ), 0 );
        t.remove( "tags", "solo" );
        assertEquals( List.of(), t.view().get( "tags" ) );
    }
}
