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

import com.wikantik.work.ItemDraft;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateItemToolTest {

    private MockWork w;
    private CreateItemTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        w = new MockWork();
        tool = new CreateItemTool( w.svc );
    }

    private static Map< String, Object > args( final Object... kv ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            m.put( ( String ) kv[ i ], kv[ i + 1 ] );
        }
        return m;
    }

    @Test
    void happyPathCreatesAnUnassignedDraftAndReturnsKeyAndVersion() throws SQLException {
        when( w.svc.createItem( any(), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-5", WorkStatus.DRAFT, 1, null, null ) ) );

        final var r = tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "Do it", "body", "Why",
            "kind", "docs", "priority", 2, "queue", "implementer", "parentKey", "PLAT-2", "productKey", "web" ) );

        final var out = WorkMcpFixture.json( r );
        assertEquals( "PLAT-5", out.get( "key" ).getAsString() );
        assertEquals( 1, out.get( "version" ).getAsInt() );
        assertEquals( "draft", out.get( "status" ).getAsString() );
        final ArgumentCaptor< ItemDraft > d = ArgumentCaptor.forClass( ItemDraft.class );
        verify( w.svc ).createItem( org.mockito.ArgumentMatchers.eq( w.agent ), d.capture() );
        assertEquals( new ItemDraft( "PLAT", WorkLevel.TASK, "PLAT-2", "web", WorkKind.DOCS, "Do it", "Why", 2, null,
            null, "implementer", null, null ), d.getValue() );
        assertNull( d.getValue().assignee() );
    }

    @Test
    void kindDefaultsToSoftware() throws SQLException {
        when( w.svc.createItem( any(), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-5", WorkStatus.DRAFT, 1, null, null ) ) );
        tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "T" ) );
        final ArgumentCaptor< ItemDraft > d = ArgumentCaptor.forClass( ItemDraft.class );
        verify( w.svc ).createItem( any(), d.capture() );
        assertEquals( WorkKind.SOFTWARE, d.getValue().kind() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "", "  " } )
    void aBlankTypeOrKindIsAsGoodAsNoneAndTheServiceChoosesTheDefault( final String blank ) throws SQLException {
        when( w.svc.createItem( any(), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-5", WorkStatus.DRAFT, 1, null, null ) ) );
        tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "T", "type", blank, "kind", blank ) );
        final ArgumentCaptor< ItemDraft > d = ArgumentCaptor.forClass( ItemDraft.class );
        verify( w.svc ).createItem( any(), d.capture() );
        assertNull( d.getValue().type() );
        assertEquals( WorkKind.SOFTWARE, d.getValue().kind() );
    }

    @Test
    void noTypeAndNoLabelsReachTheServiceAsNull() throws SQLException {
        when( w.svc.createItem( any(), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-5", WorkStatus.DRAFT, 1, null, null ) ) );
        tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "T" ) );
        final ArgumentCaptor< ItemDraft > d = ArgumentCaptor.forClass( ItemDraft.class );
        verify( w.svc ).createItem( any(), d.capture() );
        assertNull( d.getValue().type() );
        assertNull( d.getValue().labels() );
    }

    @Test
    void typeAndLabelsArePassedToTheService() throws SQLException {
        when( w.svc.createItem( any(), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-5", WorkStatus.DRAFT, 1, null, null ) ) );
        tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "T", "type", "bug",
            "labels", java.util.List.of( "ui" ) ) );
        final ArgumentCaptor< ItemDraft > d = ArgumentCaptor.forClass( ItemDraft.class );
        verify( w.svc ).createItem( any(), d.capture() );
        assertEquals( com.wikantik.work.WorkType.BUG, d.getValue().type() );
        assertEquals( java.util.List.of( "ui" ), d.getValue().labels() );
    }

    @Test
    void labelsMustBeAListOfText() {
        final var r = tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "T", "labels", "ui" ) );
        assertEquals( Boolean.TRUE, r.isError() );
        assertEquals( "labels: must be a list of labels.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void anUnknownTypeIsAValidationErrorByName() {
        final var r = tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "T", "type", "epic" ) );
        assertEquals( Boolean.TRUE, r.isError() );
        assertEquals( "type: Unknown type: epic", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void nullActorRefusedAndServiceNeverCalled() {
        final var r = tool.call( null, args( "project", "PLAT", "level", "task", "title", "T" ) );
        assertEquals( "This endpoint needs a work API key.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void hiddenAndUnknownProjectGiveIdenticalError() throws SQLException {
        when( w.svc.createItem( any(), any() ) ).thenThrow( new WorkNotFoundException( "a" ) );
        final var a = tool.call( w.agent, args( "project", "SECRET", "level", "task", "title", "T" ) );
        final var b = tool.call( w.agent, args( "project", "NOPE", "level", "task", "title", "T" ) );
        assertEquals( "Not found.", WorkMcpFixture.text( a ) );
        assertEquals( a, b );
    }

    @Test
    void statusIsRejectedByNameBecauseNewItemsAreDrafts() {
        final var r = tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "T", "status", "ready" ) );
        assertEquals( Boolean.TRUE, r.isError() );
        assertEquals( "status: New items always start as drafts.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void assigneeIsRejectedByNameWithAPointerToHandoff() {
        final var r = tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "T", "assignee", "Lena Lead" ) );
        assertEquals( "assignee: Use handoff_item to change who has the item.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @ParameterizedTest
    @ValueSource( strings = { "autonomy", "doneMeans", "allowDirect", "dueOn", "rank", "version", "colour" } )
    void otherFieldsAreRejectedByName( final String field ) {
        final var r = tool.call( w.agent, args( "project", "PLAT", "level", "task", "title", "T", field, "x" ) );
        assertEquals( Boolean.TRUE, r.isError() );
        assertEquals( true, WorkMcpFixture.text( r ).startsWith( field + ": " ), WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void requiredAndTypedArgumentsAreCheckedByName() {
        assertEquals( "project: is required.", WorkMcpFixture.text( tool.call( w.agent, args( "level", "task", "title", "T" ) ) ) );
        assertEquals( "level: is required.", WorkMcpFixture.text( tool.call( w.agent, args( "project", "P", "title", "T" ) ) ) );
        assertEquals( "title: is required.", WorkMcpFixture.text( tool.call( w.agent, args( "project", "P", "level", "task", "title", " " ) ) ) );
        assertEquals( "priority: must be a number.", WorkMcpFixture.text(
            tool.call( w.agent, args( "project", "P", "level", "task", "title", "T", "priority", "x" ) ) ) );
        assertEquals( "body: must be a string.", WorkMcpFixture.text(
            tool.call( w.agent, args( "project", "P", "level", "task", "title", "T", "body", 1 ) ) ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void validationDeniedAndConflictAreMapped() throws SQLException {
        when( w.svc.createItem( any(), any() ) ).thenThrow( new WorkValidationException( "parentKey", "No such item in this project." ) )
            .thenThrow( new WorkDeniedException( "You may not create items here." ) )
            .thenThrow( new WorkConflictException( "Archived.", WorkConflictException.ARCHIVED ) );
        final var a = args( "project", "PLAT", "level", "task", "title", "T" );
        assertEquals( "parentKey: No such item in this project.", WorkMcpFixture.text( tool.call( w.agent, a ) ) );
        assertEquals( "denied: You may not create items here.", WorkMcpFixture.text( tool.call( w.agent, a ) ) );
        assertEquals( "archived: Archived.", WorkMcpFixture.text( tool.call( w.agent, a ) ) );
    }

    @Test
    void anUnknownLevelOrKindIsAValidationErrorByName() {
        assertEquals( true, WorkMcpFixture.text( tool.call( w.agent,
            args( "project", "P", "level", "saga", "title", "T" ) ) ).startsWith( "level: " ) );
        assertEquals( true, WorkMcpFixture.text( tool.call( w.agent,
            args( "project", "P", "level", "task", "kind", "magic", "title", "T" ) ) ).startsWith( "kind: " ) );
        verifyNoInteractions( w.svc );
    }
}
