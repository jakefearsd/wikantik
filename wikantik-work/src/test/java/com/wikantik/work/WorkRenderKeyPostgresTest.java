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
package com.wikantik.work;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An item's body and every comment are rendered with the key of the item they belong to, never without one. */
@RequiresPostgres
class WorkRenderKeyPostgresTest {

    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final List< String > keys = new ArrayList<>();
    private final List< String > texts = new ArrayList<>();
    private boolean oneArgumentCalled;

    private WorkClaimFixtures fx;
    private WorkService svc;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
        fx.store.putMember( fx.plat.id(), "lena", "user", WorkRole.LEAD, lena );
        final WorkRenderer recording = new WorkRenderer() {
            @Override
            public String toHtml( final String markdown ) {
                oneArgumentCalled = true;
                return markdown;
            }

            @Override
            public String toHtml( final String markdown, final String itemKey ) {
                texts.add( markdown );
                keys.add( itemKey );
                return markdown;
            }
        };
        svc = new WorkService( fx.store, recording, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.systemUTC(), WorkSettings.DEFAULTS, new WorkListener() { } );
    }

    @Test
    void theBodyAndEveryCommentRenderWithTheItemKey() throws SQLException {
        final Item item = fx.ready( "one" );
        svc.updateItem( lena, item.key(), new ItemPatch( item.version(), Map.of( "body", "the body" ), null ) );
        keys.clear();
        texts.clear();

        svc.item( lena, item.key() );
        assertEquals( List.of( item.key() ), keys, "the body" );

        final CommentView added = svc.addComment( lena, item.key(), "first" );
        assertEquals( item.key(), keys.get( keys.size() - 1 ), "addComment" );
        assertEquals( "first", texts.get( texts.size() - 1 ) );

        svc.editComment( lena, added.id(), "second" );
        assertEquals( item.key(), keys.get( keys.size() - 1 ), "editComment" );
        assertEquals( "second", texts.get( texts.size() - 1 ) );

        keys.clear();
        svc.item( lena, item.key() );
        assertEquals( List.of( item.key(), item.key() ), keys, "the body and the one comment" );
        assertTrue( keys.stream().noneMatch( k -> k == null ) );
        assertFalse( oneArgumentCalled, "the one-argument method is never called" );
    }
}
