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
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.MetaJson;
import com.wikantik.collab.model.DraftSummary;
import com.wikantik.rest.CollabWire.Answer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * {@code GET /api/collab/{page}/draft} past the session lookup (section 6.2's draft summary, the publish dialog's
 * input): the session's {@link CollabSession#draftSummary} snapshot, taken under its lock, then the HTML diff of the
 * published body against the draft head rendered <em>outside</em> any session lock, and the meta changes.
 *
 * <p>The body is exactly the fixture's ({@code draft.get.200}): {@code {basePageVersion, version, unpublished,
 * contributors, lastEditAt, diff, metaChanges}}. {@code diff} is the wiki's own diff ({@code DifferenceManager.makeDiff},
 * HTML-escaped by the diff provider, the same HTML {@code /api/diff?format=html} returns) and the empty string when the
 * text is unchanged. It is not filtered for access rules or comments: a participant already holds the raw draft text.
 * {@code metaChanges} lists the top-level fields whose {@link MetaJson#normalise normalised} values differ, in the
 * published page's field order followed by the fields the draft added in the draft's order; a removed field has
 * {@code after: null}, an added one {@code before: null}.</p>
 *
 * <p>Any refusal of the session (403 {@code not-your-participant}, 403 {@code evicted} with its {@code detail}, 409
 * {@code rejoin}) is answered as every post-join call answers it. A diff of up to {@code maxPageBytes} per call is
 * worth bounding: each participant may fetch {@value #BURST} summaries at once and {@value #PER_SECOND} a second after
 * that (429 {@code rate-limited} with {@code retryAfterMs}). The order, inside {@link CollabSession#draftSummary}, is
 * the identity check, then the budget, then the snapshot: the budget is spent only by an
 * identity-checked participant, so another user's refused call never uses it up, and a rate-limited call copies no
 * snapshot.</p>
 */
final class CollabDraft {

    private static final Logger LOG = LogManager.getLogger( CollabDraft.class );
    /** Summaries a participant may fetch at once. */
    static final int BURST = 3;
    /** Summaries a participant may fetch per second after its burst. */
    static final int PER_SECOND = 1;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** The participants whose budgets are remembered; the least recently used is forgotten beyond this. */
    private static final int MAX_TRACKED = 10_000;

    /** Renders the HTML diff of two texts of a page; production asks the wiki's {@code DifferenceManager}. */
    @FunctionalInterface
    interface Differ {
        String diff( String pageName, String from, String to );
    }

    /** One participant's token bucket. */
    private static final class Bucket {
        double tokens = BURST;
        long refilledAt;

        Bucket( final long now ) {
            this.refilledAt = now;
        }
    }

    private final Differ differ;
    private final LongSupplier nanos;
    /** Access-ordered: the least recently asking participant is forgotten first. Guarded by itself. */
    private final Map< String, Bucket > buckets = new LinkedHashMap<>( 64, 0.75f, true ) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry( final Map.Entry< String, Bucket > eldest ) {
            return size() > MAX_TRACKED;
        }
    };

    CollabDraft( final Differ differ, final LongSupplier nanos ) {
        this.differ = Objects.requireNonNull( differ, "differ" );
        this.nanos = Objects.requireNonNull( nanos, "nanos" );
    }

    /** The summary for {@code participantId}, or the refusal; {@code observe} gets the session's answer. */
    Answer summarise( final CollabSession s, final String participantId, final String authorKey,
                      final BiConsumer< Integer, String > observe ) {
        // under the session lock: the identity check, then this budget (only for an identity-checked participant), then
        // the snapshot (only when the budget admitted it)
        final long[] waitNanos = { 0 };
        final DraftSummary d = s.draftSummary( participantId, authorKey, () -> {
            waitNanos[ 0 ] = acquire( s.id() + "/" + participantId );
            return waitNanos[ 0 ] == 0;
        } );
        if( waitNanos[ 0 ] > 0 ) {
            LOG.info( "collab session {}: draft summary for {} refused: rate-limited", s.id(), participantId );
            final Answer a = CollabWire.reason( 429, "rate-limited" );
            ( ( JsonObject ) a.body() ).addProperty( "retryAfterMs", Math.max( 1, waitNanos[ 0 ] / 1_000_000 ) );
            return a;
        }
        observe.accept( d.status(), d.reason() );
        if( d.status() != 200 ) {
            return CollabWire.refusal( d.status(), d.reason(), d.detail(), 0 );
        }
        final JsonObject o = new JsonObject();
        o.addProperty( "basePageVersion", d.basePageVersion() );
        o.addProperty( "version", d.version() );
        o.addProperty( "metaSeq", d.metaSeq() );
        o.addProperty( "unpublished", d.unpublished() );
        o.add( "contributors", CollabWire.GSON.toJsonTree( d.contributors() ) );
        o.addProperty( "lastEditAt", d.lastEditAt() == null ? null : d.lastEditAt().toString() );
        o.addProperty( "diff", d.baseBody().equals( d.doc() ) ? "" : differ.diff( s.pageName(), d.baseBody(), d.doc() ) );
        o.add( "metaChanges", metaChanges( d.baseMeta(), d.meta() ) );
        return new Answer( 200, o );
    }

    /** Takes one token: 0 when granted, else the nanoseconds until the next token. */
    private long acquire( final String key ) {
        final long now = nanos.getAsLong();
        synchronized( buckets ) {
            final Bucket b = buckets.computeIfAbsent( key, k -> new Bucket( now ) );
            final long elapsed = Math.max( 0L, now - b.refilledAt );
            b.tokens = Math.min( BURST, b.tokens + elapsed * (double) PER_SECOND / NANOS_PER_SECOND );
            b.refilledAt = now;
            if( b.tokens >= 1d ) {
                b.tokens -= 1d;
                return 0;
            }
            return (long) Math.ceil( ( 1d - b.tokens ) * NANOS_PER_SECOND / PER_SECOND );
        }
    }

    /**
     * The top-level fields whose normalised values differ, as {@code {field, before, after}}: the base's fields in the
     * base's order, then the fields only {@code meta} has, in its order. Both sides go through {@link MetaJson#normalise}
     * first (A14), so a YAML {@code Date} and its JSON string, or an Integer and a Long, are no change.
     */
    static JsonArray metaChanges( final Map< String, Object > base, final Map< String, Object > meta ) {
        final Map< String, Object > before = MetaJson.normalise( base );
        final Map< String, Object > after = MetaJson.normalise( meta );
        final JsonArray out = new JsonArray();
        for( final Map.Entry< String, Object > e : before.entrySet() ) {
            final Object now = after.get( e.getKey() );
            if( !Objects.equals( e.getValue(), now ) ) {
                out.add( change( e.getKey(), e.getValue(), now ) );
            }
        }
        for( final Map.Entry< String, Object > e : after.entrySet() ) {
            if( !before.containsKey( e.getKey() ) && e.getValue() != null ) {
                out.add( change( e.getKey(), null, e.getValue() ) );
            }
        }
        return out;
    }

    private static JsonObject change( final String field, final Object before, final Object after ) {
        final JsonObject c = new JsonObject();
        c.addProperty( "field", field );
        c.add( "before", CollabWire.GSON.toJsonTree( before ) );
        c.add( "after", CollabWire.GSON.toJsonTree( after ) );
        return c;
    }
}
