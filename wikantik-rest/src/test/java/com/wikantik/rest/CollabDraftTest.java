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

import com.wikantik.collab.CollabSession;
import com.wikantik.collab.model.DraftSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CollabDraft}'s order of work, against a session that behaves as {@code Gate.summary} does (the identity check,
 * then the caller's budget, then the snapshot): a rate-limited call copies no snapshot, and a refused identity spends no
 * token.
 */
class CollabDraftTest {

    private CollabSession session;
    private CollabDraft draft;
    private final List< Integer > observed = new ArrayList<>();
    /** How many snapshots the session copied. */
    private int snapshots;

    @BeforeEach
    void setUp() {
        session = mock( CollabSession.class );
        when( session.id() ).thenReturn( UUID.fromString( "00000000-0000-0000-0000-000000000001" ) );
        when( session.pageName() ).thenReturn( "Page" );
        when( session.draftSummary( anyString(), anyString(), any() ) ).thenAnswer( i -> {
            if( !"ada".equals( i.getArgument( 1 ) ) ) {
                return DraftSummary.refused( 403, "not-your-participant", null );   // identity first: no budget asked
            }
            if( !i.< BooleanSupplier >getArgument( 2 ).getAsBoolean() ) {
                return DraftSummary.refused( 429, "rate-limited", null );
            }
            snapshots++;
            return new DraftSummary( 200, null, null, 1, 0, false, List.of(), null, "text", "text", Map.of(), Map.of() );
        } );
        draft = new CollabDraft( ( page, from, to ) -> "", () -> 0L );
    }

    private CollabWire.Answer summarise( final String author ) {
        return draft.summarise( session, "pt-1", author, ( status, reason ) -> observed.add( status ) );
    }

    @Test
    void aRateLimitedSummaryCopiesNoSnapshot() {
        for( int i = 0; i < CollabDraft.BURST; i++ ) {
            assertEquals( 200, summarise( "ada" ).status() );
        }
        final CollabWire.Answer limited = summarise( "ada" );
        assertEquals( 429, limited.status() );
        assertTrue( limited.body().getAsJsonObject().get( "retryAfterMs" ).getAsLong() > 0 );
        assertEquals( CollabDraft.BURST, snapshots, "the refused call copied nothing" );
    }

    @Test
    void aRefusedIdentitySpendsNoToken() {
        for( int i = 0; i < CollabDraft.BURST + 2; i++ ) {
            assertEquals( 403, summarise( "mallory" ).status() );
        }
        assertEquals( 0, snapshots );
        assertEquals( List.of( 403, 403, 403, 403, 403 ), observed, "every refusal is reported to the registry" );
        for( int i = 0; i < CollabDraft.BURST; i++ ) {
            assertEquals( 200, summarise( "ada" ).status(), "the participant's own budget is untouched" );
        }
    }
}
