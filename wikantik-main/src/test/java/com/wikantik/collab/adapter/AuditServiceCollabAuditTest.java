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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditQuery;
import com.wikantik.audit.AuditService;
import com.wikantik.audit.PersistedAuditEntry;
import com.wikantik.collab.CollabLogCapture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3 Task 5: {@link AuditServiceCollabAudit}'s entries (section 9) and its failure handling. */
class AuditServiceCollabAuditTest {

    private static final UUID SESSION = UUID.fromString( "00000000-0000-0000-0000-00000000c011" );

    /** Keeps what it is given. */
    private static class RecordingService implements AuditService {
        final List< AuditEntry > got = new ArrayList<>();

        @Override
        public void record( final AuditEntry entry ) {
            got.add( entry );
        }

        @Override
        public List< PersistedAuditEntry > query( final AuditQuery query ) {
            return List.of();
        }

        @Override
        public Optional< Long > verifyChain( final long fromSeq, final long toSeq ) {
            return Optional.empty();
        }

        @Override
        public long droppedCount() {
            return 0;
        }
    }

    private static JsonObject detail( final AuditEntry e ) {
        final JsonObject o = JsonParser.parseString( e.detail() ).getAsJsonObject();
        assertEquals( SESSION.toString(), o.get( "session" ).getAsString(), "every entry names the session" );
        return o;
    }

    private static void assertEntry( final AuditEntry e, final AuditCategory category, final String type,
                                     final String actor, final String actorType ) {
        assertEquals( category, e.category() );
        assertEquals( type, e.eventType() );
        assertEquals( actor, e.actorPrincipal() );
        assertEquals( actorType, e.actorType() );
        assertEquals( "page", e.targetType() );
        assertEquals( "Page", e.targetId() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
        assertNotNull( e.eventTime() );
    }

    @Test
    void entriesHaveTheDocumentedTypesAndDetail() {
        final RecordingService svc = new RecordingService();
        final AuditServiceCollabAudit audit = new AuditServiceCollabAudit( () -> svc );

        audit.sessionOpened( SESSION, "Page", "alice" );
        audit.joined( SESSION, "Page", "bob", "p-2" );
        audit.published( SESSION, "Page", "alice", 7, 42, new LinkedHashSet<>( List.of( "bob", "alice" ) ) );
        audit.discarded( SESSION, "Page", "bob" );
        audit.evicted( SESSION, "Page", "bob", "lost-edit" );
        audit.purged( SESSION, "Page", "root", false );
        audit.purged( SESSION, "Page", null, true );
        audit.orphaned( SESSION, "Page" );

        assertEquals( 8, svc.got.size() );
        assertEntry( svc.got.get( 0 ), AuditCategory.CONTENT, "collab.session.open", "alice", "user" );
        assertEquals( 1, detail( svc.got.get( 0 ) ).size() );

        assertEntry( svc.got.get( 1 ), AuditCategory.CONTENT, "collab.join", "bob", "user" );
        assertEquals( "p-2", detail( svc.got.get( 1 ) ).get( "participantId" ).getAsString() );

        assertEntry( svc.got.get( 2 ), AuditCategory.CONTENT, "collab.publish", "alice", "user" );
        final JsonObject published = detail( svc.got.get( 2 ) );
        assertEquals( 7, published.get( "pageVersion" ).getAsInt() );
        assertEquals( 42, published.get( "collabVersion" ).getAsInt() );
        assertEquals( "[\"bob\",\"alice\"]", published.get( "contributors" ).toString(),
            "the contributors' author keys, in credit order (an admin-only surface)" );

        assertEntry( svc.got.get( 3 ), AuditCategory.CONTENT, "collab.discard", "bob", "user" );
        assertEntry( svc.got.get( 4 ), AuditCategory.CONTENT, "collab.evict", "bob", "user" );
        assertEquals( "lost-edit", detail( svc.got.get( 4 ) ).get( "reason" ).getAsString() );

        assertEntry( svc.got.get( 5 ), AuditCategory.ADMIN, "collab.purge", "root", "user" );
        assertEntry( svc.got.get( 6 ), AuditCategory.ADMIN, "collab.expire", null, "system" );
        assertEntry( svc.got.get( 7 ), AuditCategory.CONTENT, "collab.orphaned", null, "system" );
        detail( svc.got.get( 7 ) );
    }

    @Test
    void aNullAuditServiceIsAWarnNotAThrow() {
        final AuditServiceCollabAudit missing = new AuditServiceCollabAudit( () -> null );
        final AuditServiceCollabAudit broken = new AuditServiceCollabAudit( () -> new RecordingService() {
            @Override
            public void record( final AuditEntry entry ) {
                throw new IllegalStateException( "test: the audit queue is closed" );
            }
        } );
        final List< LogEvent > warnings = CollabLogCapture.warnings( () -> {
            assertDoesNotThrow( () -> missing.joined( SESSION, "Page", "bob", "p-2" ) );
            assertDoesNotThrow( () -> broken.discarded( SESSION, "Page", "bob" ) );
        } );
        assertEquals( 2, warnings.stream().filter( e -> e.getLevel() == Level.WARN ).count(), warnings.toString() );
        assertTrue( warnings.get( 0 ).getMessage().getFormattedMessage().contains( "collab.join" ) );
        assertTrue( warnings.get( 1 ).getMessage().getFormattedMessage().contains( "collab.discard" ) );
        assertTrue( warnings.get( 1 ).getMessage().getFormattedMessage().contains( "the audit queue is closed" ) );
    }
}
