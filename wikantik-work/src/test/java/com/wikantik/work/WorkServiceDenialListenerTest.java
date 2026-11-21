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

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link WorkService} reports each {@link WorkDeniedException} it throws to its denial listener. */
class WorkServiceDenialListenerTest {

    private static final Instant NOW = Instant.parse( "2026-11-15T12:00:00Z" );

    private final WorkStore store = mock( WorkStore.class );
    private static final WorkDirectory DIRECTORY = new WorkDirectory() {
        @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
        @Override public boolean exists( final String login ) { return true; }
        @Override public String displayName( final String login ) { return login; }
    };
    private final List< Action > seen = new ArrayList<>();
    private final WorkService service = new WorkService( store, md -> md, DIRECTORY,
        Clock.fixed( NOW, ZoneOffset.UTC ), seen::add );
    private final WorkService silent = new WorkService( store, md -> md, DIRECTORY,
        Clock.fixed( NOW, ZoneOffset.UTC ) );
    private final Actor viewer = Actor.human( "vera", Set.of(), false );

    private void projectWithViewer() throws SQLException {
        when( store.project( "PLAT" ) ).thenReturn(
            Optional.of( new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, NOW, null ) ) );
        when( store.roleFor( org.mockito.ArgumentMatchers.eq( 1L ), any() ) ).thenReturn( Optional.of( WorkRole.VIEWER ) );
    }

    @Test
    void aProjectScopedDenialIsReportedWithItsAction() throws SQLException {
        projectWithViewer();
        assertThrows( WorkDeniedException.class, () -> service.createItem( viewer,
            new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", null, null, null, null, null, null ) ) );
        assertEquals( List.of( Action.CREATE_ITEM ), seen );
    }

    @Test
    void anInstanceLevelDenialIsReportedWithItsAction() throws SQLException {
        assertThrows( WorkDeniedException.class, () -> service.createProject( viewer, "NEWP", "New", "" ) );
        assertEquals( List.of( Action.MANAGE_INSTANCE ), seen );
    }

    @Test
    void withoutAListenerADenialStillThrows() throws SQLException {
        assertThrows( WorkDeniedException.class, () -> silent.createProject( viewer, "NEWP", "New", "" ) );
    }
}
