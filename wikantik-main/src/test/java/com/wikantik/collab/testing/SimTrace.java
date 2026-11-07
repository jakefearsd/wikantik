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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One simulated run, serialisable to JSON (F1, and the F3 export format Task 13 replays through the real library).
 *
 * <p>Layout: {@code format}, {@code seed}, {@code profile}, {@code config} (page name, page-size limit, ring size, the
 * save-filter rewrite, client count), {@code initialDoc} (as seeded into the page writer, before its line-ending
 * normalisation), {@code steps} (ordered; each has a stable {@code id}, an {@code action} and only the fields that action
 * uses), {@code outcome} ({@code status}, {@code message}, {@code at}, and {@code kind} for a failure) and {@code clients}: per client its name, author
 * key and the {@link SimClient} trace entries ({@code join}, {@code local} with ChangeSet JSON, {@code receive} with
 * {@code fromVersion} and updates, {@code sendable} snapshots, ...). Field order is fixed and no map is hash-ordered, so
 * the same run always serialises to the same bytes.</p>
 *
 * <p>Replay reads only the header and the steps: the outcome and client traces are regenerated.</p>
 */
public final class SimTrace {

    static final int FORMAT = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public enum Status { PASS, FAIL, UNSUPPORTED }

    /**
     * Where a run ended. {@code at} is the step id ({@code "12"}), a quiescence round ({@code "q3"}) or {@code "end"}.
     * {@code kind} identifies a failure precisely enough for the shrinker to keep "the same failure" (F1): {@code
     * "invariant N"}, {@code "convergence/doc"} and the other convergence and protocol sub-kinds, or {@code "exception
     * <class> at <first frame>"}; for a run that did not fail it is the status name.
     */
    public record Outcome( Status status, String message, String at, String kind ) {

        public static final Outcome NOT_RUN = new Outcome( Status.PASS, "not run", "" );
        private static final Pattern INVARIANT = Pattern.compile( "^invariant (\\d+) .*", Pattern.DOTALL );

        public Outcome {
            Objects.requireNonNull( status );
            Objects.requireNonNull( message );
            Objects.requireNonNull( at );
            Objects.requireNonNull( kind );
        }

        /** An outcome whose kind is derived from the message ({@link #kindOf}). */
        public Outcome( final Status status, final String message, final String at ) {
            this( status, message, at, kindOf( status, message ) );
        }

        /**
         * The kind of a failure message: {@code "invariant N"} for an invariant violation, otherwise the message up to its
         * first colon; the status name when the run did not fail. The scheduler passes sharper kinds for its own failures.
         */
        public static String kindOf( final Status status, final String message ) {
            if( status != Status.FAIL ) {
                return status.name();
            }
            final Matcher m = INVARIANT.matcher( message );
            if( m.matches() ) {
                return "invariant " + m.group( 1 );
            }
            final int c = message.indexOf( ':' );
            return c < 0 ? message : message.substring( 0, c );
        }

        /** Both runs failed, the same way (same {@link #kind}). */
        public boolean sameFailure( final Outcome other ) {
            return status == Status.FAIL && other.status == Status.FAIL && kind.equals( other.kind );
        }

        /**
         * The coarse failure class: the message up to its first colon ({@code "invariant 2 (...) violated"},
         * {@code "convergence"}, {@code "protocol"}, {@code "exception"}), or the status name when the run did not fail.
         * The shrinker matches on {@link #kind} instead.
         */
        public String category() {
            if( status != Status.FAIL ) {
                return status.name();
            }
            final int c = message.indexOf( ':' );
            return c < 0 ? message : message.substring( 0, c );
        }
    }

    /**
     * One scheduled action. Unused fields are -1 / null / 0. {@code op} is the request kind a {@code TIMEOUT} abandons
     * ({@code push}, {@code pull}, {@code join}, {@code publish}).
     */
    public record Step( int id, SimProfile.Kind action, int client, String messageId, int from, int to, String insert,
                        long millis, String op ) {

        static Step of( final int id, final SimProfile.Kind action ) {
            return new Step( id, action, -1, null, -1, -1, null, 0, null );
        }

        Step client( final int c ) {
            return new Step( id, action, c, messageId, from, to, insert, millis, op );
        }

        Step message( final String m ) {
            return new Step( id, action, client, m, from, to, insert, millis, op );
        }

        Step edit( final int f, final int t, final String ins ) {
            return new Step( id, action, client, messageId, f, t, ins, millis, op );
        }

        Step millis( final long ms ) {
            return new Step( id, action, client, messageId, from, to, insert, ms, op );
        }

        Step op( final String o ) {
            return new Step( id, action, client, messageId, from, to, insert, millis, o );
        }

        JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty( "id", id );
            o.addProperty( "action", action.name() );
            if( client >= 0 ) {
                o.addProperty( "client", client );
            }
            if( messageId != null ) {
                o.addProperty( "messageId", messageId );
            }
            if( insert != null ) {
                o.addProperty( "from", from );
                o.addProperty( "to", to );
                o.addProperty( "insert", insert );
            }
            if( millis != 0 ) {
                o.addProperty( "millis", millis );
            }
            if( op != null ) {
                o.addProperty( "op", op );
            }
            return o;
        }

        static Step fromJson( final JsonObject o ) {
            return new Step( o.get( "id" ).getAsInt(), SimProfile.Kind.valueOf( o.get( "action" ).getAsString() ),
                o.has( "client" ) ? o.get( "client" ).getAsInt() : -1,
                o.has( "messageId" ) ? o.get( "messageId" ).getAsString() : null,
                o.has( "from" ) ? o.get( "from" ).getAsInt() : -1,
                o.has( "to" ) ? o.get( "to" ).getAsInt() : -1,
                o.has( "insert" ) ? o.get( "insert" ).getAsString() : null,
                o.has( "millis" ) ? o.get( "millis" ).getAsLong() : 0,
                o.has( "op" ) ? o.get( "op" ).getAsString() : null );
        }
    }

    /** A client's identity and its {@link SimClient#trace()} (compact JSON per entry). */
    public record ClientTrace( String name, String authorKey, List< String > entries ) {
    }

    private final long seed;
    private final SimProfile profile;
    private final String pageName;
    private final long maxPageBytes;
    private final int ringSize;
    private final String rewrite;
    private final int clients;
    private final String initialDoc;
    private final List< Step > steps;
    private final Outcome outcome;
    private final List< ClientTrace > clientTraces;
    private final Map< String, Long > counters;

    SimTrace( final long seed, final SimProfile profile, final String pageName, final long maxPageBytes, final int ringSize,
              final String rewrite, final int clients, final String initialDoc, final List< Step > steps,
              final Outcome outcome, final List< ClientTrace > clientTraces ) {
        this( seed, profile, pageName, maxPageBytes, ringSize, rewrite, clients, initialDoc, steps, outcome, clientTraces,
            Map.of() );
    }

    private SimTrace( final long seed, final SimProfile profile, final String pageName, final long maxPageBytes,
                      final int ringSize, final String rewrite, final int clients, final String initialDoc,
                      final List< Step > steps, final Outcome outcome, final List< ClientTrace > clientTraces,
                      final Map< String, Long > counters ) {
        this.seed = seed;
        this.profile = Objects.requireNonNull( profile );
        this.pageName = Objects.requireNonNull( pageName );
        this.maxPageBytes = maxPageBytes;
        this.ringSize = ringSize;
        this.rewrite = Objects.requireNonNull( rewrite );
        this.clients = clients;
        this.initialDoc = Objects.requireNonNull( initialDoc );
        this.steps = List.copyOf( steps );
        this.outcome = Objects.requireNonNull( outcome );
        this.clientTraces = List.copyOf( clientTraces );
        this.counters = Collections.unmodifiableMap( new LinkedHashMap<>( counters ) );
    }

    public long seed() {
        return seed;
    }

    public SimProfile profile() {
        return profile;
    }

    public String pageName() {
        return pageName;
    }

    public long maxPageBytes() {
        return maxPageBytes;
    }

    public int ringSize() {
        return ringSize;
    }

    public String rewrite() {
        return rewrite;
    }

    public int clients() {
        return clients;
    }

    public String initialDoc() {
        return initialDoc;
    }

    public List< Step > steps() {
        return steps;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** Per client, its trace entries as compact JSON strings. */
    public List< List< String > > clientTraces() {
        return clientTraces.stream().map( ClientTrace::entries ).toList();
    }

    /**
     * What the run explored (F1 coverage, not serialised): per counter name (see {@code SimScheduler}) how often it
     * happened. Empty for a trace that was loaded or not yet run.
     */
    public Map< String, Long > counters() {
        return counters;
    }

    List< ClientTrace > clientTraceRecords() {
        return clientTraces;
    }

    /** The same header with other steps; outcome and client traces are cleared (they belong to a replay). */
    public SimTrace withSteps( final List< Step > newSteps ) {
        return new SimTrace( seed, profile, pageName, maxPageBytes, ringSize, rewrite, clients, initialDoc, newSteps,
            Outcome.NOT_RUN, List.of() );
    }

    SimTrace withResult( final Outcome o, final List< ClientTrace > traces, final Map< String, Long > runCounters ) {
        return new SimTrace( seed, profile, pageName, maxPageBytes, ringSize, rewrite, clients, initialDoc, steps, o, traces,
            runCounters );
    }

    public String toJson() {
        final JsonObject root = new JsonObject();
        root.addProperty( "format", FORMAT );
        root.addProperty( "seed", seed );
        root.addProperty( "profile", profile.name() );
        final JsonObject config = new JsonObject();
        config.addProperty( "pageName", pageName );
        config.addProperty( "maxPageBytes", maxPageBytes );
        config.addProperty( "ringSize", ringSize );
        config.addProperty( "rewrite", rewrite );
        config.addProperty( "clients", clients );
        root.add( "config", config );
        root.addProperty( "initialDoc", initialDoc );
        final JsonArray st = new JsonArray();
        steps.forEach( s -> st.add( s.toJson() ) );
        root.add( "steps", st );
        final JsonObject out = new JsonObject();
        out.addProperty( "status", outcome.status().name() );
        out.addProperty( "message", outcome.message() );
        out.addProperty( "at", outcome.at() );
        if( outcome.status() == Status.FAIL ) {
            out.addProperty( "kind", outcome.kind() );
        }
        root.add( "outcome", out );
        final JsonArray cl = new JsonArray();
        for( final ClientTrace c : clientTraces ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "name", c.name() );
            o.addProperty( "authorKey", c.authorKey() );
            final JsonArray entries = new JsonArray();
            c.entries().forEach( e -> entries.add( JsonParser.parseString( e ) ) );
            o.add( "trace", entries );
            cl.add( o );
        }
        root.add( "clients", cl );
        return escapeLoneSurrogates( GSON.toJson( root ) ) + "\n";
    }

    /**
     * Gson writes characters raw, so a lone surrogate (a failing run's broken document, a message cut mid-pair) would make
     * the JSON unencodable as UTF-8. JSON structure is ASCII, so any surrogate is inside a string: escape the unpaired ones
     * as {@code \\uXXXX}, which parses back to the same char.
     */
    static String escapeLoneSurrogates( final String json ) {
        final StringBuilder sb = new StringBuilder( json.length() );
        for( int i = 0; i < json.length(); i++ ) {
            final char c = json.charAt( i );
            final boolean pairedHigh = Character.isHighSurrogate( c ) && i + 1 < json.length()
                && Character.isLowSurrogate( json.charAt( i + 1 ) );
            if( pairedHigh ) {
                sb.append( c ).append( json.charAt( ++i ) );
            } else if( Character.isSurrogate( c ) ) {
                sb.append( String.format( "\\u%04x", (int) c ) );
            } else {
                sb.append( c );
            }
        }
        return sb.toString();
    }

    public static SimTrace fromJson( final String json ) {
        final JsonObject root = JsonParser.parseString( json ).getAsJsonObject();
        final int format = root.get( "format" ).getAsInt();
        if( format != FORMAT ) {
            throw new IllegalArgumentException( "unsupported trace format " + format );
        }
        final JsonObject config = root.getAsJsonObject( "config" );
        final List< Step > steps = new ArrayList<>();
        for( final JsonElement e : root.getAsJsonArray( "steps" ) ) {
            steps.add( Step.fromJson( e.getAsJsonObject() ) );
        }
        Outcome outcome = Outcome.NOT_RUN;
        if( root.has( "outcome" ) ) {
            final JsonObject o = root.getAsJsonObject( "outcome" );
            final Status st = Status.valueOf( o.get( "status" ).getAsString() );
            final String message = o.get( "message" ).getAsString();
            outcome = new Outcome( st, message, o.get( "at" ).getAsString(),
                o.has( "kind" ) ? o.get( "kind" ).getAsString() : Outcome.kindOf( st, message ) );
        }
        final List< ClientTrace > traces = new ArrayList<>();
        if( root.has( "clients" ) ) {
            for( final JsonElement e : root.getAsJsonArray( "clients" ) ) {
                final JsonObject c = e.getAsJsonObject();
                final List< String > entries = new ArrayList<>();
                c.getAsJsonArray( "trace" ).forEach( x -> entries.add( x.toString() ) );
                traces.add( new ClientTrace( c.get( "name" ).getAsString(), c.get( "authorKey" ).getAsString(), entries ) );
            }
        }
        return new SimTrace( root.get( "seed" ).getAsLong(), SimProfile.valueOf( root.get( "profile" ).getAsString() ),
            config.get( "pageName" ).getAsString(), config.get( "maxPageBytes" ).getAsLong(),
            config.get( "ringSize" ).getAsInt(), config.get( "rewrite" ).getAsString(), config.get( "clients" ).getAsInt(),
            root.get( "initialDoc" ).getAsString(), steps, outcome, traces );
    }
}
