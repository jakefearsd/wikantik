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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the optional {@code opportunities} array jakemon's ingest payload carries alongside its
 * {@code by_page}/{@code by_query} visibility rows (content-intelligence design §7.3 "Imported
 * from jakemon", §12.1 item J3) into {@link ImportedOpportunityRow}s.
 *
 * <p>Sits next to {@link SnapshotPayloadParser} rather than inside it -- the two parse disjoint
 * keys of the same request body (visibility rows vs. detector opportunities) and each has its own
 * per-row shape and rejection rules; keeping them separate keeps each file's fail-soft contract
 * easy to audit in isolation.</p>
 *
 * <p>Same contract as {@link SnapshotPayloadParser}: this <strong>never throws</strong>. A
 * malformed payload, an unknown detector type, or a disallowed engine/site is counted as rejected
 * rather than propagated -- {@code POST /admin/insights/ingest} is admin-authed but LAN-facing,
 * fed by an external shipper, and a bad shipper run must not be able to 500 the endpoint.</p>
 */
public final class ImportedOpportunityParser {

    private static final Logger LOG = LogManager.getLogger( ImportedOpportunityParser.class );

    /** The five detector types jakemon ships (design §7.3 "Imported from jakemon"). No sixth type
     *  is accepted -- an unrecognised type is a shape jakemon has not documented shipping, and
     *  silently storing it would let a typo or a future jakemon change through unnoticed. */
    private static final Set< String > KNOWN_TYPES = Set.of(
            "striking_distance", "ctr_gap", "content_gap", "cannibalization", "decay" );

    /** Payload keys consumed into a typed {@link ImportedOpportunityRow} field -- everything else
     *  in a row lands in {@code evidenceJson} unchanged. {@code confidence} and {@code engine} are
     *  excluded here (not merely stored as columns) because {@link JdbcInsightsStore} re-adds them
     *  to the evidence map on read -- see {@link OpportunityEngine}'s {@code EVIDENCE_ENGINE} key,
     *  which {@code suppressDivergenceAffected} depends on finding there. */
    private static final Set< String > CONSUMED_KEYS = Set.of(
            "type", "query", "target_page", "engine", "site", "expected_uplift", "confidence" );

    /** Wildcard allowlist entry meaning "accept every value" -- matches
     *  {@link SnapshotPayloadParser}'s {@code WILDCARD}. */
    private static final String WILDCARD = "*";

    /**
     * One parse outcome.
     *
     * @param rows      the rows successfully extracted
     * @param rejected  count of rows dropped for any reason (blank/unknown type, disallowed
     *                  engine/site, non-numeric {@code expected_uplift}, or neither {@code query}
     *                  nor {@code target_page} present) -- plus 1 if the {@code opportunities}
     *                  array itself was present but malformed (not a JSON array)
     * @param present   whether the {@code opportunities} key was present in the payload at all --
     *                  callers use this to avoid changing their response shape when jakemon has
     *                  not yet started sending the key (§12.1: "until J3 ... nothing supplies it")
     */
    public record ParseResult( List< ImportedOpportunityRow > rows, int rejected, boolean present ) {}

    private static final ParseResult ABSENT = new ParseResult( List.of(), 0, false );

    private ImportedOpportunityParser() {}

    /**
     * Parses the {@code opportunities} array out of a jakemon ingest payload.
     *
     * @param json           the raw ingest request body (the same document
     *                       {@link SnapshotPayloadParser#parse} reads {@code by_page}/
     *                       {@code by_query} from) -- {@code as_of} is taken from its top-level
     *                       {@code snapshot_date}
     * @param allowedEngines allowed {@code engine} values per row, or a set containing {@code "*"}
     *                       to allow any engine
     * @param allowedSites   allowed {@code site} values per row, or a set containing {@code "*"}
     *                       to allow any site
     * @return the rows extracted, a rejection count, and whether the key was present; never throws
     */
    public static ParseResult parse( final String json, final Set< String > allowedEngines,
                                     final Set< String > allowedSites ) {
        try {
            final JsonObject o = JsonParser.parseString( json ).getAsJsonObject();
            if ( !o.has( "opportunities" ) || o.get( "opportunities" ).isJsonNull() ) {
                return ABSENT;
            }

            final JsonArray arr;
            try {
                arr = o.getAsJsonArray( "opportunities" );
            } catch ( final RuntimeException e ) {
                LOG.warn( "rejecting 'opportunities' payload key: not a JSON array: {}", e.getMessage() );
                return new ParseResult( List.of(), 1, true );
            }

            final LocalDate asOf = o.has( "snapshot_date" )
                    ? LocalDate.parse( o.get( "snapshot_date" ).getAsString() ) : null;
            if ( asOf == null ) {
                LOG.warn( "rejecting imported opportunities: payload has no snapshot_date to stamp them with" );
                return new ParseResult( List.of(), 1, true );
            }

            final List< ImportedOpportunityRow > rows = new ArrayList<>();
            int rejected = 0;
            for ( final JsonElement el : arr ) {
                try {
                    final ImportedOpportunityRow row =
                            parseRow( el.getAsJsonObject(), asOf, allowedEngines, allowedSites );
                    if ( row == null ) {
                        rejected++;
                    } else {
                        rows.add( row );
                    }
                } catch ( final RuntimeException e ) {
                    LOG.warn( "rejecting malformed imported-opportunity row: {}", e.getMessage() );
                    rejected++;
                }
            }
            return new ParseResult( rows, rejected, true );
        } catch ( final RuntimeException e ) {
            LOG.warn( "rejecting malformed imported-opportunities payload: {}", e.getMessage() );
            return new ParseResult( List.of(), 1, false );
        }
    }

    /** @return the parsed row, or {@code null} if this row fails a rejection rule -- never throws
     *          for a reason logged here; a genuinely malformed field (e.g. {@code confidence} not
     *          a number) propagates and is caught, and counted, by the caller's loop. */
    private static ImportedOpportunityRow parseRow( final JsonObject r, final LocalDate asOf,
                                                     final Set< String > allowedEngines,
                                                     final Set< String > allowedSites ) {
        final String type = optString( r, "type" );
        if ( type == null || type.isBlank() || !KNOWN_TYPES.contains( type ) ) {
            LOG.warn( "rejecting imported opportunity: blank or unknown type '{}'", type );
            return null;
        }

        final String engine = optString( r, "engine" );
        final String site = optString( r, "site" );
        if ( engine == null || engine.isBlank() || site == null || site.isBlank() ) {
            LOG.warn( "rejecting imported opportunity type={}: blank engine/site", type );
            return null;
        }
        if ( !isAllowed( allowedEngines, engine ) || !isAllowed( allowedSites, site ) ) {
            LOG.warn( "rejecting imported opportunity type={} for engine={} site={} (not allowlisted)",
                    type, engine, site );
            return null;
        }

        if ( !r.has( "expected_uplift" ) || r.get( "expected_uplift" ).isJsonNull() ) {
            LOG.warn( "rejecting imported opportunity type={}: missing expected_uplift", type );
            return null;
        }
        final double expectedUplift;
        try {
            expectedUplift = r.get( "expected_uplift" ).getAsDouble();
        } catch ( final NumberFormatException | UnsupportedOperationException e ) {
            LOG.warn( "rejecting imported opportunity type={}: non-numeric expected_uplift", type );
            return null;
        }

        final String query = optString( r, "query" );
        final String targetPage = optString( r, "target_page" );
        final String target = ( query != null && !query.isBlank() ) ? query
                : ( targetPage != null && !targetPage.isBlank() ) ? targetPage : null;
        if ( target == null ) {
            LOG.warn( "rejecting imported opportunity type={}: neither query nor target_page identifies anything",
                    type );
            return null;
        }

        final Double confidence = ( r.has( "confidence" ) && !r.get( "confidence" ).isJsonNull() )
                ? r.get( "confidence" ).getAsDouble() : null;

        final JsonObject evidence = new JsonObject();
        for ( final String key : r.keySet() ) {
            if ( !CONSUMED_KEYS.contains( key ) ) {
                evidence.add( key, r.get( key ) );
            }
        }

        return new ImportedOpportunityRow( asOf, engine, site, type, target, expectedUplift,
                confidence, evidence.toString() );
    }

    private static String optString( final JsonObject r, final String key ) {
        return ( r.has( key ) && !r.get( key ).isJsonNull() ) ? r.get( key ).getAsString() : null;
    }

    private static boolean isAllowed( final Set< String > allowlist, final String value ) {
        return allowlist.contains( WILDCARD ) || allowlist.contains( value );
    }
}
