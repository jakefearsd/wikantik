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
import com.wikantik.work.DoraReport;
import com.wikantik.work.Durations;
import com.wikantik.work.FlowReport;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkFlowJsonTest {

    private static final LocalDate FROM = LocalDate.parse( "2026-11-02" );
    private static final LocalDate TO = LocalDate.parse( "2026-11-15" );

    private static FlowReport flow( final Durations cycle ) {
        return new FlowReport( FROM, TO, "Europe/Berlin", List.of( new FlowReport.Week( FROM, 0, BigDecimal.ZERO ),
            new FlowReport.Week( LocalDate.parse( "2026-11-09" ), 2, new BigDecimal( "4.5" ) ) ), cycle,
            new Durations( 2, Duration.ofHours( 3 ), Duration.ofHours( 5 ) ),
            List.of( new FlowReport.Wip( WorkStatus.IN_PROGRESS, 3, List.of( new FlowReport.WipItem( "PLAT-1", "A title",
                Duration.ofMinutes( 90 ) ) ) ), new FlowReport.Wip( WorkStatus.REVIEW, 0, List.of() ) ) );
    }

    private static DoraReport dora( final Durations lead, final DoraReport.Rate rate ) {
        return new DoraReport( FROM, TO, "UTC", null, List.of( new DoraReport.WeekCount( FROM, 4 ) ), lead, 1, 2, 6, rate,
            Durations.NONE, 3, new DoraReport.Rate( 0, 0 ), new DoraReport.MergeMix( 1, 2, 3, 4, 5 ), 7 );
    }

    @Test
    void durationsAreSecondsAndNullWhenEmpty() {
        final JsonObject o = WorkFlowJson.flow( flow( Durations.NONE ) );
        final JsonObject cycle = o.getAsJsonObject( "cycleTime" );
        assertEquals( 0, cycle.get( "n" ).getAsInt() );
        assertTrue( cycle.get( "p50Seconds" ).isJsonNull() );
        assertTrue( cycle.get( "p85Seconds" ).isJsonNull() );
        final JsonObject lead = o.getAsJsonObject( "leadTime" );
        assertEquals( 10_800, lead.get( "p50Seconds" ).getAsLong() );
        assertEquals( 18_000, lead.get( "p85Seconds" ).getAsLong() );
        final JsonObject item = o.getAsJsonArray( "wip" ).get( 0 ).getAsJsonObject().getAsJsonArray( "items" ).get( 0 )
            .getAsJsonObject();
        assertEquals( 5_400, item.get( "ageSeconds" ).getAsLong() );
        assertEquals( "in_progress", o.getAsJsonArray( "wip" ).get( 0 ).getAsJsonObject().get( "status" ).getAsString() );
        assertEquals( "Europe/Berlin", o.get( "zone" ).getAsString() );
    }

    @Test
    void aWeekWithoutPointsWritesZero() {
        final JsonArray weeks = WorkFlowJson.flow( flow( Durations.NONE ) ).getAsJsonArray( "throughput" );
        assertEquals( 0, weeks.get( 0 ).getAsJsonObject().get( "points" ).getAsBigDecimal().signum() );
        assertEquals( 0, weeks.get( 0 ).getAsJsonObject().get( "items" ).getAsInt() );
        assertEquals( new BigDecimal( "4.5" ), weeks.get( 1 ).getAsJsonObject().get( "points" ).getAsBigDecimal() );
        assertEquals( "2026-11-09", weeks.get( 1 ).getAsJsonObject().get( "weekStart" ).getAsString() );
    }

    @Test
    void doraRatesKeepNumeratorAndDenominator() {
        final JsonObject o = WorkFlowJson.dora( dora( Durations.NONE, new DoraReport.Rate( 4, 7 ) ) );
        assertEquals( 4, o.getAsJsonObject( "changeFailureRate" ).get( "numerator" ).getAsInt() );
        assertEquals( 7, o.getAsJsonObject( "changeFailureRate" ).get( "denominator" ).getAsInt() );
        assertEquals( 0, o.getAsJsonObject( "reworkRate" ).get( "denominator" ).getAsInt() );
        assertTrue( o.get( "product" ).isJsonNull() );
        assertEquals( 1, o.get( "changesWithoutCommitTime" ).getAsInt() );
        assertEquals( 2, o.get( "changesWithClampedCommitTime" ).getAsInt() );
        assertEquals( 6, o.get( "changesWithoutUsableCommitTime" ).getAsInt() );
        assertEquals( 7, o.get( "excluded" ).getAsInt() );
        assertEquals( 30, o.get( "maxCommitLeadDays" ).getAsInt() );
        assertEquals( 3, o.get( "unrecovered" ).getAsInt() );
        assertEquals( 5, o.getAsJsonObject( "merges" ).get( "flaggedUnacknowledged" ).getAsInt() );
        assertEquals( 4, o.getAsJsonArray( "deploymentFrequency" ).get( 0 ).getAsJsonObject().get( "deployments" ).getAsInt() );
    }

    @Test
    void anEmptyWindowSerialisesWithoutNaNOrInfinity() {
        final DoraReport empty = new DoraReport( FROM, TO, "UTC", "web", List.of(), Durations.NONE, 0, 0, 0,
            new DoraReport.Rate( 0, 0 ), Durations.NONE, 0, new DoraReport.Rate( 0, 0 ),
            new DoraReport.MergeMix( 0, 0, 0, 0, 0 ), 0 );
        final String text = WorkJson.GSON.toJson( WorkFlowJson.dora( empty ) );
        assertFalse( text.contains( "NaN" ) || text.contains( "Infinity" ), text );
        assertTrue( WorkFlowJson.dora( empty ).getAsJsonObject( "leadTimeForChanges" ).get( "p50Seconds" ).isJsonNull() );
        final String flowText = WorkJson.GSON.toJson( WorkFlowJson.flow( new FlowReport( FROM, TO, "UTC", List.of(),
            Durations.NONE, Durations.NONE, List.of() ) ) );
        assertFalse( flowText.contains( "NaN" ) || flowText.contains( "Infinity" ), flowText );
    }

    @Test
    void datesAreCheckedAtParseTime() {
        assertNull( WorkFlowJson.date( "from", null ) );
        assertNull( WorkFlowJson.date( "from", "  " ) );
        assertEquals( FROM, WorkFlowJson.date( "from", "2026-11-02" ) );
        for ( final String bad : List.of( "2026-13-01", "11/02/2026", "yesterday", "2026-02-30", "+12345-01-01" ) ) {
            final WorkValidationException e = assertThrows( WorkValidationException.class,
                () -> WorkFlowJson.date( "to", bad ), bad );
            assertEquals( "to", e.field(), bad );
        }
        assertEquals( "Use a date like 2026-11-21.", assertThrows( WorkValidationException.class,
            () -> WorkFlowJson.date( "from", "soon" ) ).getMessage() );
        assertEquals( "from", assertThrows( WorkValidationException.class, () -> WorkFlowJson.date( "from", "1969-12-31" ) ).field() );
        assertEquals( "to", assertThrows( WorkValidationException.class, () -> WorkFlowJson.date( "to", "10000-01-01" ) ).field() );
        assertEquals( LocalDate.parse( "9999-12-31" ), WorkFlowJson.date( "to", "9999-12-31" ) );
        assertEquals( LocalDate.parse( "1970-01-01" ), WorkFlowJson.date( "from", "1970-01-01" ) );
    }

    @Test
    void aSprintIsNamedByItsNumber() {
        assertNull( WorkFlowJson.sprint( null ) );
        assertEquals( 7L, WorkFlowJson.sprint( "7" ) );
        for ( final String bad : List.of( "x", "-1", "1.5", "active", "12345678901234567890" ) ) {
            final WorkValidationException e = assertThrows( WorkValidationException.class,
                () -> WorkFlowJson.sprint( bad ), bad );
            assertEquals( "sprint", e.field() );
            assertEquals( "A sprint is named by its number.", e.getMessage() );
        }
    }
}
