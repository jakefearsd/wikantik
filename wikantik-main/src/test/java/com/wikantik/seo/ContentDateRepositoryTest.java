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
package com.wikantik.seo;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class ContentDateRepositoryTest {

    private static final Instant T1 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final Instant T2 = Instant.parse( "2026-02-01T00:00:00Z" );

    private ContentDateRepository repo;

    @BeforeEach
    void setUp() {
        final var ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "page_content_dates" );
        repo = new ContentDateRepository( ds );
    }

    @Test
    void upsertThenFindRoundTrips() {
        repo.upsert( new ContentDate( "A", T1, 3 ) );
        assertEquals( new ContentDate( "A", T1, 3 ), repo.find( "A" ).orElseThrow() );
        assertTrue( repo.find( "Missing" ).isEmpty() );
    }

    @Test
    void upsertTwiceIsOneRowWithLatestValues() {
        repo.upsert( new ContentDate( "A", T1, 1 ) );
        repo.upsert( new ContentDate( "A", T2, 5 ) );
        assertEquals( Map.of( "A", T2 ), repo.findAll() );
        assertEquals( 5, repo.find( "A" ).orElseThrow().baselineVersion() );
    }

    @Test
    void findAllReturnsEveryPage() {
        repo.upsert( new ContentDate( "A", T1, 1 ) );
        repo.upsert( new ContentDate( "B", T2, 2 ) );
        assertEquals( Map.of( "A", T1, "B", T2 ), repo.findAll() );
    }

    @Test
    void deleteRemovesRow() {
        repo.upsert( new ContentDate( "A", T1, 1 ) );
        repo.delete( "A" );
        assertTrue( repo.find( "A" ).isEmpty() );
    }

    @Test
    void renameMovesRowAndReplacesAnyRowAtTarget() {
        repo.upsert( new ContentDate( "Old", T1, 1 ) );
        repo.upsert( new ContentDate( "New", T2, 9 ) );
        repo.rename( "Old", "New" );
        assertTrue( repo.find( "Old" ).isEmpty() );
        assertEquals( new ContentDate( "New", T1, 1 ), repo.find( "New" ).orElseThrow() );
    }
}
