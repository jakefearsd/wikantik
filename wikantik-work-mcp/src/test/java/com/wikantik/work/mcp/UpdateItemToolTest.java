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
package com.wikantik.work.mcp;

import com.wikantik.work.ItemPatch;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UpdateItemToolTest {

    private MockWork w;
    private UpdateItemTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        w = new MockWork();
        tool = new UpdateItemTool( w.svc, w.directory );
    }

    private static Map< String, Object > args( final Object... kv ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            m.put( ( String ) kv[ i ], kv[ i + 1 ] );
        }
        return m;
    }

    @Test
    void happyPathPassesOnlyTheSettableFieldsAndTheNote() throws SQLException {
        when( w.svc.updateItem( eq( w.agent ), eq( "PLAT-1" ), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", WorkStatus.IN_PROGRESS, 4, null, null ) ) );

        final var r = tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 3, "title", "New", "status",
            "in_progress", "priority", 2.0, "dueOn", null, "note", "Started" ) );

        assertTrue( r.isError() == null || !r.isError(), WorkMcpFixture.text( r ) );
        assertEquals( "PLAT-1", WorkMcpFixture.json( r ).get( "key" ).getAsString() );
        final ArgumentCaptor< ItemPatch > patch = ArgumentCaptor.forClass( ItemPatch.class );
        verify( w.svc ).updateItem( eq( w.agent ), eq( "PLAT-1" ), patch.capture() );
        assertEquals( 3, patch.getValue().expectedVersion() );
        assertEquals( "Started", patch.getValue().note() );
        final Map< String, Object > expected = new LinkedHashMap<>();
        expected.put( "title", "New" );
        expected.put( "status", "in_progress" );
        expected.put( "priority", 2 );
        expected.put( "dueOn", null );
        assertEquals( expected, patch.getValue().changes(), "a null value clears the field" );
    }

    @Test
    void typeAndLabelsAreSettable() throws SQLException {
        when( w.svc.updateItem( eq( w.agent ), eq( "PLAT-1" ), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", WorkStatus.IN_PROGRESS, 4, null, null ) ) );
        tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 3, "type", "bug",
            "labels", java.util.List.of( "ui", "api" ) ) );
        final ArgumentCaptor< ItemPatch > patch = ArgumentCaptor.forClass( ItemPatch.class );
        verify( w.svc ).updateItem( eq( w.agent ), eq( "PLAT-1" ), patch.capture() );
        assertEquals( "bug", patch.getValue().changes().get( "type" ) );
        assertEquals( java.util.List.of( "ui", "api" ), patch.getValue().changes().get( "labels" ) );
    }

    @Test
    void labelsMustBeAListOfText() {
        final var r = tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 3, "labels", "ui" ) );
        assertEquals( Boolean.TRUE, r.isError() );
        assertEquals( "labels: must be a list of labels.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void nullActorRefusedAndServiceNeverCalled() {
        final var r = tool.call( null, args( "key", "PLAT-1", "expectedVersion", 1, "title", "x" ) );
        assertEquals( "This endpoint needs a work API key.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void hiddenAndUnknownGiveIdenticalError() throws SQLException {
        when( w.svc.updateItem( any(), eq( "HIDDEN-1" ), any() ) ).thenThrow( new WorkNotFoundException( "x" ) );
        when( w.svc.updateItem( any(), eq( "PLAT-999" ), any() ) ).thenThrow( new WorkNotFoundException( "y" ) );
        final var a = tool.call( w.agent, args( "key", "HIDDEN-1", "expectedVersion", 1, "title", "x" ) );
        final var b = tool.call( w.agent, args( "key", "PLAT-999", "expectedVersion", 1, "title", "x" ) );
        assertEquals( "Not found.", WorkMcpFixture.text( a ) );
        assertEquals( a, b );
    }

    @Test
    void assigneeAndQueueAreRejectedByNameWithAPointerToHandoff() {
        for ( final String field : new String[] { "assignee", "queue" } ) {
            final var r = tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 1, field, "x" ) );
            assertEquals( Boolean.TRUE, r.isError() );
            assertEquals( field + ": Use handoff_item to change who has the item.", WorkMcpFixture.text( r ) );
        }
        verifyNoInteractions( w.svc );
    }

    @ParameterizedTest
    @ValueSource( strings = { "autonomy", "doneMeans", "allowDirect", "parentKey", "productKey", "level", "kind",
        "rank", "project" } )
    void policyAndStructuralFieldsAreRejectedByName( final String field ) {
        final var r = tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 1, "title", "ok", field, "x" ) );
        assertEquals( Boolean.TRUE, r.isError() );
        assertEquals( field + ": That field is not settable through this endpoint.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void anUnknownArgumentIsRejectedByName() {
        final var r = tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 1, "colour", "red" ) );
        assertEquals( "colour: Unknown argument.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void expectedVersionIsRequiredAndTyped() {
        assertEquals( "expectedVersion: is required.",
            WorkMcpFixture.text( tool.call( w.agent, args( "key", "PLAT-1", "title", "x" ) ) ) );
        assertEquals( "expectedVersion: must be a number.",
            WorkMcpFixture.text( tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", "3", "title", "x" ) ) ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void wrongTypedFieldsAreRejectedByName() {
        assertEquals( "title: must be a string.", WorkMcpFixture.text(
            tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 1, "title", 5 ) ) ) );
        assertEquals( "priority: must be a number.", WorkMcpFixture.text(
            tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 1, "priority", "high" ) ) ) );
        assertEquals( "key: must be a string.", WorkMcpFixture.text(
            tool.call( w.agent, args( "key", 4, "expectedVersion", 1, "title", "x" ) ) ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void nothingToChangeIsRefusedBeforeTheService() throws SQLException {
        final var r = tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 1, "note", "just a note" ) );
        assertEquals( "changes: Nothing to change.", WorkMcpFixture.text( r ) );
        verify( w.svc, never() ).updateItem( any(), any(), any() );
    }

    @Test
    void staleCarriesTheCurrentVersion() throws SQLException {
        when( w.svc.updateItem( any(), any(), any() ) ).thenThrow( new WorkConflictException( "The item changed." ) );
        when( w.svc.item( any(), eq( "PLAT-1" ) ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", WorkStatus.READY, 9, null, null ) ) );
        final var r = tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 1, "title", "x" ) );
        assertEquals( "stale: The item changed. (currentVersion: 9)", WorkMcpFixture.text( r ) );
    }

    @Test
    void deniedAndValidationAndArchivedAreMapped() throws SQLException {
        when( w.svc.updateItem( any(), eq( "A-1" ), any() ) ).thenThrow( new WorkDeniedException( "Claimed by another agent." ) );
        when( w.svc.updateItem( any(), eq( "A-2" ), any() ) ).thenThrow( new WorkValidationException( "status", "Unknown status: x" ) );
        when( w.svc.updateItem( any(), eq( "A-3" ), any() ) )
            .thenThrow( new WorkConflictException( "Archived.", WorkConflictException.ARCHIVED ) );
        assertEquals( "denied: Claimed by another agent.",
            WorkMcpFixture.text( tool.call( w.agent, args( "key", "A-1", "expectedVersion", 1, "title", "x" ) ) ) );
        assertEquals( "status: Unknown status: x",
            WorkMcpFixture.text( tool.call( w.agent, args( "key", "A-2", "expectedVersion", 1, "title", "x" ) ) ) );
        assertEquals( "archived: Archived.",
            WorkMcpFixture.text( tool.call( w.agent, args( "key", "A-3", "expectedVersion", 1, "title", "x" ) ) ) );
    }

    private ItemPatch patchOf( final Object... kv ) throws SQLException {
        when( w.svc.updateItem( eq( w.agent ), eq( "PLAT-1" ), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", WorkStatus.IN_PROGRESS, 4, null, null ) ) );
        final Map< String, Object > a = args( "key", "PLAT-1", "expectedVersion", 3 );
        a.putAll( args( kv ) );
        final var r = tool.call( w.agent, a );
        assertTrue( r.isError() == null || !r.isError(), WorkMcpFixture.text( r ) );
        final ArgumentCaptor< ItemPatch > patch = ArgumentCaptor.forClass( ItemPatch.class );
        verify( w.svc ).updateItem( eq( w.agent ), eq( "PLAT-1" ), patch.capture() );
        return patch.getValue();
    }

    @Test
    void anEstimateIsPassedToTheService() throws SQLException {
        assertEquals( 2.5, ( ( Number ) patchOf( "estimate", 2.5 ).changes().get( "estimate" ) ).doubleValue() );
    }

    @Test
    void aNullEstimateClearsIt() throws SQLException {
        final ItemPatch p = patchOf( "estimate", null );
        assertTrue( p.changes().containsKey( "estimate" ) );
        assertEquals( null, p.changes().get( "estimate" ) );
    }

    @Test
    void aTextEstimateIsRefusedNamingEstimate() {
        final var r = tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 3, "estimate", "lots" ) );
        assertEquals( "estimate: must be a number.", WorkMcpFixture.text( r ) );
    }

    @Test
    void aSprintIsRefusedWithThePlanningText() throws SQLException {
        final var r = tool.call( w.agent, args( "key", "PLAT-1", "expectedVersion", 3, "sprint", 7 ) );
        assertEquals( Boolean.TRUE, r.isError() );
        assertEquals( "sprint: A person plans a sprint.", WorkMcpFixture.text( r ) );
        verify( w.svc, never() ).updateItem( any(), any(), any() );
    }
}
