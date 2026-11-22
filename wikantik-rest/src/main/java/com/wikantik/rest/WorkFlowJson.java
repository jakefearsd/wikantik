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
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wikantik.work.DoraReport;
import com.wikantik.work.Durations;
import com.wikantik.work.FlowReport;
import com.wikantik.work.WorkValidationException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * The wire form of the flow and DORA reports, and the parsing of their query parameters. Durations are whole seconds
 * and {@code null} when the sample is empty; a rate is a numerator and a denominator, never a quotient, so an empty
 * window can never write NaN or Infinity.
 */
final class WorkFlowJson {

    /** The earliest and latest year a date parameter may name. */
    static final int MIN_YEAR = 1970;
    static final int MAX_YEAR = 9999;
    static final String DATE_HINT = "Use a date like 2026-11-21.";
    private static final Pattern DIGITS = Pattern.compile( "\\d{1,18}" );

    private WorkFlowJson() {
    }

    /** A {@code yyyy-MM-dd} date between the years 1970 and 9999; null for none; a 400 naming {@code field} otherwise. */
    static LocalDate date( final String field, final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        final LocalDate parsed;
        try {
            parsed = LocalDate.parse( raw.trim() );
        } catch ( final DateTimeParseException e ) {
            throw new WorkValidationException( field, DATE_HINT );
        }
        if ( parsed.getYear() < MIN_YEAR || parsed.getYear() > MAX_YEAR ) {
            throw new WorkValidationException( field, "The year must be between " + MIN_YEAR + " and " + MAX_YEAR + "." );
        }
        return parsed;
    }

    /** A sprint number (digits); null for none. */
    static Long sprint( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        if ( !DIGITS.matcher( raw.trim() ).matches() ) {
            throw new WorkValidationException( "sprint", "A sprint is named by its number." );
        }
        return Long.parseLong( raw.trim() );
    }

    static JsonObject flow( final FlowReport r ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "from", r.from().toString() );
        o.addProperty( "to", r.to().toString() );
        o.addProperty( "zone", r.zone() );
        final JsonArray weeks = new JsonArray();
        for ( final FlowReport.Week w : r.throughput() ) {
            final JsonObject week = new JsonObject();
            week.addProperty( "weekStart", w.weekStart().toString() );
            week.addProperty( "items", w.items() );
            week.addProperty( "points", w.points() );
            weeks.add( week );
        }
        o.add( "throughput", weeks );
        o.add( "cycleTime", durations( r.cycleTime() ) );
        o.add( "leadTime", durations( r.leadTime() ) );
        final JsonArray wip = new JsonArray();
        for ( final FlowReport.Wip w : r.wip() ) {
            final JsonObject status = new JsonObject();
            status.addProperty( "status", w.status().wire() );
            status.addProperty( "count", w.count() );
            final JsonArray items = new JsonArray();
            for ( final FlowReport.WipItem i : w.items() ) {
                final JsonObject item = new JsonObject();
                item.addProperty( "key", i.key() );
                item.addProperty( "title", i.title() );
                item.addProperty( "ageSeconds", i.age().toSeconds() );
                items.add( item );
            }
            status.add( "items", items );
            wip.add( status );
        }
        o.add( "wip", wip );
        return o;
    }

    static JsonObject dora( final DoraReport r ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "from", r.from().toString() );
        o.addProperty( "to", r.to().toString() );
        o.addProperty( "zone", r.zone() );
        o.add( "product", r.productKey() == null ? JsonNull.INSTANCE : new JsonPrimitive( r.productKey() ) );
        final JsonArray weeks = new JsonArray();
        for ( final DoraReport.WeekCount w : r.deploymentFrequency() ) {
            final JsonObject week = new JsonObject();
            week.addProperty( "weekStart", w.weekStart().toString() );
            week.addProperty( "deployments", w.deployments() );
            weeks.add( week );
        }
        o.add( "deploymentFrequency", weeks );
        o.add( "leadTimeForChanges", durations( r.leadTimeForChanges() ) );
        o.addProperty( "changesWithoutCommitTime", r.changesWithoutCommitTime() );
        o.addProperty( "changesWithClampedCommitTime", r.changesWithClampedCommitTime() );
        o.addProperty( "changesWithoutUsableCommitTime", r.changesWithoutUsableCommitTime() );
        o.addProperty( "maxCommitLeadDays", maxCommitLeadDays() );
        o.add( "changeFailureRate", rate( r.changeFailureRate() ) );
        o.add( "recoveryTime", durations( r.recoveryTime() ) );
        o.addProperty( "unrecovered", r.unrecovered() );
        o.add( "reworkRate", rate( r.reworkRate() ) );
        final JsonObject m = new JsonObject();
        m.addProperty( "reviewed", r.merges().reviewed() );
        m.addProperty( "unreviewed", r.merges().unreviewed() );
        m.addProperty( "direct", r.merges().direct() );
        m.addProperty( "flagged", r.merges().flagged() );
        m.addProperty( "flaggedUnacknowledged", r.merges().flaggedUnacknowledged() );
        o.add( "merges", m );
        return o;
    }

    private static long maxCommitLeadDays() {
        return DoraReport.MAX_COMMIT_LEAD.toDays();
    }

    private static JsonObject rate( final DoraReport.Rate rate ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "numerator", rate.numerator() );
        o.addProperty( "denominator", rate.denominator() );
        return o;
    }

    private static JsonObject durations( final Durations d ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "n", d.n() );
        o.add( "p50Seconds", seconds( d.p50() ) );
        o.add( "p85Seconds", seconds( d.p85() ) );
        return o;
    }

    private static JsonElement seconds( final Duration d ) {
        return d == null ? JsonNull.INSTANCE : new JsonPrimitive( d.toSeconds() );
    }
}
