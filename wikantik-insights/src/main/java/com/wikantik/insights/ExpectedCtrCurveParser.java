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
package com.wikantik.insights;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the optional top-level {@code expected_ctr} object jakemon's ingest payload carries
 * alongside its {@code by_page}/{@code by_query} visibility rows and its {@code opportunities}
 * array (content-intelligence design §7.3 rule 2, "jakemon's {@code EXPECTED_CTR} curve stays the
 * single place the CTR model is defined") into a position-&gt;CTR map ready for
 * {@link ExpectedCtrCurve#fromTable} / {@link InsightsStore#upsertCtrCurve}.
 *
 * <p>Sits next to {@link SnapshotPayloadParser} and {@link ImportedOpportunityParser} rather than
 * inside either -- all three parse disjoint keys of the same request body, and each has its own
 * per-row shape and rejection rules; keeping them separate keeps each file's fail-soft contract
 * easy to audit in isolation.</p>
 *
 * <p>Same contract as its siblings: this <strong>never throws</strong>. {@code POST
 * /admin/insights/ingest} is admin-authed but LAN-facing, fed by an external shipper, and a bad
 * shipper run must not be able to 500 the endpoint. A malformed {@code expected_ctr} value is
 * rejected entry-by-entry rather than failing the whole payload -- one bad key must not throw away
 * the other nine good ones.</p>
 */
public final class ExpectedCtrCurveParser {

    private static final Logger LOG = LogManager.getLogger( ExpectedCtrCurveParser.class );

    /**
     * One parse outcome.
     *
     * @param asOf     the date to stamp the curve with ({@code null} unless {@code present} and
     *                 the payload carried a valid {@code snapshot_date})
     * @param points   position -&gt; CTR, for every entry that parsed cleanly
     * @param rejected count of individual {@code expected_ctr} entries dropped (non-integer key,
     *                 non-numeric value) -- plus 1 if {@code expected_ctr} itself was present but
     *                 malformed (not a JSON object), or if {@code snapshot_date} was missing/
     *                 unparseable
     * @param present  whether the {@code expected_ctr} key was present in the payload at all --
     *                 callers use this to avoid changing their response shape for a payload that
     *                 predates jakemon shipping this key
     */
    public record ParseResult( LocalDate asOf, Map<Integer, Double> points, int rejected, boolean present ) {}

    private static final ParseResult ABSENT = new ParseResult( null, Map.of(), 0, false );

    private ExpectedCtrCurveParser() {}

    /**
     * Parses the {@code expected_ctr} object out of a jakemon ingest payload.
     *
     * @param json the raw ingest request body (the same document {@link SnapshotPayloadParser}
     *             and {@link ImportedOpportunityParser} read from) -- {@code asOf} is taken from
     *             its top-level {@code snapshot_date}
     * @return the parsed curve points, a rejection count, and whether the key was present; never
     *         throws
     */
    public static ParseResult parse( final String json ) {
        try {
            final JsonObject o = JsonParser.parseString( json ).getAsJsonObject();
            if ( !o.has( "expected_ctr" ) || o.get( "expected_ctr" ).isJsonNull() ) {
                return ABSENT;
            }

            final JsonObject curve;
            try {
                curve = o.getAsJsonObject( "expected_ctr" );
            } catch ( final RuntimeException e ) {
                LOG.warn( "rejecting 'expected_ctr' payload key: not a JSON object: {}", e.getMessage() );
                return new ParseResult( null, Map.of(), 1, true );
            }

            final LocalDate asOf = parseSnapshotDate( o );
            if ( asOf == null ) {
                LOG.warn( "rejecting expected_ctr curve: payload has no usable snapshot_date to stamp it with" );
                return new ParseResult( null, Map.of(), 1, true );
            }

            final Map<Integer, Double> points = new LinkedHashMap<>();
            int rejected = 0;
            for ( final Map.Entry<String, JsonElement> entry : curve.entrySet() ) {
                final Integer position = parsePosition( entry.getKey() );
                if ( position == null ) {
                    LOG.warn( "rejecting expected_ctr entry: non-integer position key '{}'", entry.getKey() );
                    rejected++;
                    continue;
                }
                final Double ctr = parseCtr( entry.getValue() );
                if ( ctr == null ) {
                    LOG.warn( "rejecting expected_ctr entry for position {}: non-numeric value '{}'",
                            position, entry.getValue() );
                    rejected++;
                    continue;
                }
                points.put( position, ctr );
            }
            return new ParseResult( asOf, points, rejected, true );
        } catch ( final RuntimeException e ) {
            LOG.warn( "rejecting malformed expected_ctr payload: {}", e.getMessage() );
            return new ParseResult( null, Map.of(), 1, false );
        }
    }

    private static LocalDate parseSnapshotDate( final JsonObject o ) {
        if ( !o.has( "snapshot_date" ) || o.get( "snapshot_date" ).isJsonNull() ) {
            return null;
        }
        try {
            return LocalDate.parse( o.get( "snapshot_date" ).getAsString() );
        } catch ( final RuntimeException e ) {
            return null;
        }
    }

    private static Integer parsePosition( final String key ) {
        try {
            return Integer.valueOf( key.trim() );
        } catch ( final NumberFormatException e ) {
            return null;
        }
    }

    private static Double parseCtr( final JsonElement value ) {
        if ( value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() ) {
            return null;
        }
        try {
            return value.getAsDouble();
        } catch ( final NumberFormatException | UnsupportedOperationException e ) {
            return null;
        }
    }
}
