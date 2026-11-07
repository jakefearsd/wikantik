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
package com.wikantik.collab.adapter;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A14: page meta from SnakeYAML survives the JSONB columns with its dates as ISO strings, whatever the JVM zone. */
@RequiresPostgres
class JdbcCollabStoreMetaTest {

    @Test
    void aYamlDateRoundTripsThroughEveryMetaColumnAsTheSameCalendarDate() {
        final TimeZone zone = TimeZone.getDefault();
        final Locale locale = Locale.getDefault();
        try {
            TimeZone.setDefault( TimeZone.getTimeZone( "America/Los_Angeles" ) );
            Locale.setDefault( Locale.US );
            PostgresTestDb.truncate( "collab_session" );
            final JdbcCollabStore store = new JdbcCollabStore( new Jdbc( PostgresTestDb.createDataSource() ) );
            final Map< String, Object > meta = FrontmatterParser.parse( "---\ndate: 2026-01-15\n---\nb\n" ).metadata();
            final UUID id = UUID.randomUUID();
            final Instant t = Instant.parse( "2026-01-01T00:00:00Z" );
            store.createSession( new SessionRecord( id, "DatedPage", SessionRecord.ACTIVE, 1, "b\n", meta,
                CollabText.contentHash( "b\n" ), 0, 0, false, null, null, null, "alice", t, null ) );
            store.writeSnapshot( id, new Snapshot( 0, 0, 1, "b\n", meta, ChangeSet.empty( 2 ) ) );
            store.appendMetaOps( id, List.of( new StoredMetaOp( 1, "alice", "date", meta.get( "date" ) ) ) );

            assertEquals( "2026-01-15", store.session( id ).orElseThrow().baseMeta().get( "date" ) );
            assertEquals( "2026-01-15", store.latestSnapshot( id ).orElseThrow().meta().get( "date" ) );
            assertEquals( "2026-01-15", store.metaOpsSince( id, 0 ).get( 0 ).value() );
        } finally {
            TimeZone.setDefault( zone );
            Locale.setDefault( locale );
        }
    }
}
