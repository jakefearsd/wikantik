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
package com.wikantik.collab;

import com.wikantik.api.frontmatter.FrontmatterParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A14: page meta (SnakeYAML) and wire/store meta (JSON) are compared only after one JSON normalisation. */
class MetaJsonTest {

    @Test
    void numbersNormaliseToLongOrDoubleAtEveryDepth() {
        final Map< String, Object > yaml = new LinkedHashMap<>();
        yaml.put( "count", 3 );
        yaml.put( "ratio", 1.5f );
        yaml.put( "tags", List.of( "a", 2 ) );
        yaml.put( "nested", Map.of( "n", (short) 7 ) );
        final Map< String, Object > n = MetaJson.normalise( yaml );
        assertEquals( 3L, n.get( "count" ) );
        assertEquals( 1.5d, n.get( "ratio" ) );
        assertEquals( List.of( "a", 2L ), n.get( "tags" ) );
        assertEquals( Map.of( "n", 7L ), n.get( "nested" ) );
        assertEquals( MetaJson.normalise( Map.of( "count", 3L ) ).get( "count" ), n.get( "count" ) );
    }

    @Test
    void nullValuesAndOrderSurvive() {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "z", null );
        m.put( "a", "x" );
        final Map< String, Object > n = MetaJson.normalise( m );
        assertTrue( n.containsKey( "z" ) );
        assertEquals( List.of( "z", "a" ), List.copyOf( n.keySet() ) );
    }

    /** Runs {@code body} with a non-UTC default zone and locale, as on a server in California. */
    static void inLosAngeles( final Runnable body ) {
        final TimeZone zone = TimeZone.getDefault();
        final Locale locale = Locale.getDefault();
        try {
            TimeZone.setDefault( TimeZone.getTimeZone( "America/Los_Angeles" ) );
            Locale.setDefault( Locale.US );
            body.run();
        } finally {
            TimeZone.setDefault( zone );
            Locale.setDefault( locale );
        }
    }

    @Test
    void aYamlDateStaysTheSameCalendarDateUnderANonUtcZone() {   // A14
        inLosAngeles( () -> {
            final Map< String, Object > meta = FrontmatterParser.parse( "---\ndate: 2026-01-15\ntitle: T\n---\nbody\n" ).metadata();
            assertTrue( meta.get( "date" ) instanceof Date, "SnakeYAML hands over a java.util.Date: " + meta.get( "date" ).getClass() );
            assertEquals( "2026-01-15", MetaJson.normalise( meta ).get( "date" ) );
            assertTrue( MetaJson.GSON.toJson( meta ).contains( "\"date\":\"2026-01-15\"" ), MetaJson.GSON.toJson( meta ) );
        } );
    }

    @Test
    void timesAreIsoInstantsAndUtcMidnightsAreDates() {
        inLosAngeles( () -> {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "instant", Instant.parse( "2026-01-15T10:30:00Z" ) );
            m.put( "dateTime", Date.from( Instant.parse( "2026-01-15T10:30:00Z" ) ) );
            m.put( "local", LocalDate.of( 2026, 1, 15 ) );
            m.put( "offset", OffsetDateTime.of( 2026, 1, 15, 2, 30, 0, 0, ZoneOffset.ofHours( -8 ) ) );
            m.put( "zonedMidnight", ZonedDateTime.of( 2026, 1, 15, 0, 0, 0, 0, ZoneId.of( "UTC" ) ) );
            final Map< String, Object > n = MetaJson.normalise( m );
            assertEquals( "2026-01-15T10:30:00Z", n.get( "instant" ) );
            assertEquals( "2026-01-15T10:30:00Z", n.get( "dateTime" ) );
            assertEquals( "2026-01-15", n.get( "local" ) );
            assertEquals( "2026-01-15T10:30:00Z", n.get( "offset" ) );
            assertEquals( "2026-01-15", n.get( "zonedMidnight" ) );
        } );
    }
}
