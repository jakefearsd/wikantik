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

import com.wikantik.work.Change;
import com.wikantik.work.ChangeState;
import com.wikantik.work.WorkChangesService.ChangeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What {@code update_change} passes to the service for each combination of arguments, and what it refuses by name. */
class UpdateChangeToolTest {

    private MockWork w;
    private UpdateChangeTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        w = new MockWork();
        tool = new UpdateChangeTool( w.svc, w.directory );
        final Change c = new Change( 5, 1, 1, "feature", "main", "abc123", null, ChangeState.OPEN, null, null, "builder",
            7L, null, null, null, null, null, null, java.time.Instant.EPOCH, null, List.of(), null, "native", null, null,
            null, null, null, null, null, null );
        when( w.changes.view( any(), anyLong() ) ).thenReturn( new ChangeView( c, "PLAT-1", "web" ) );
    }

    private static Map< String, Object > args( final Object... kv ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            m.put( ( String ) kv[ i ], kv[ i + 1 ] );
        }
        return m;
    }

    @ParameterizedTest
    @CsvSource( value = { "head_sha,abc999,NULL,NULL", "review_url,NULL,https://example.com/pull/7,NULL",
        "first_commit_at,NULL,NULL,2026-03-01T09:30:00Z" }, nullValues = "NULL" )
    void eachOfTheThreeFieldsAloneIsPassedOnAndTheRestStayUntouched( final String name, final String head,
                                                                       final String url, final String first )
        throws SQLException {
        final String value = head != null ? head : url != null ? url : first;
        final var r = tool.call( w.agent, args( "change", 5, name, value ) );
        assertTrue( r.isError() == null || !r.isError(), WorkMcpFixture.text( r ) );
        verify( w.changes ).update( w.agent, 5L, head, url, first );
        verify( w.changes, never() ).requestReview( any(), anyLong() );
        verify( w.changes, never() ).abandon( any(), anyLong(), any() );
        assertEquals( 5, WorkMcpFixture.json( r ).get( "id" ).getAsInt() );
    }

    @Test
    void anEmptyReviewUrlIsPassedOnAsEmptySoTheServiceRemovesTheLink() throws SQLException {
        tool.call( w.agent, args( "change", 5, "review_url", "" ) );
        verify( w.changes ).update( w.agent, 5L, null, "", null );
    }

    @Test
    void askingForReviewAloneUpdatesNoFieldsAndRequestsTheReview() throws SQLException {
        final var r = tool.call( w.agent, args( "change", 5, "state", "in_review" ) );
        assertTrue( r.isError() == null || !r.isError(), WorkMcpFixture.text( r ) );
        verify( w.changes, never() ).update( any(), anyLong(), any(), any(), any() );
        verify( w.changes ).requestReview( w.agent, 5L );
    }

    @Test
    void abandoningWithANoteAloneUpdatesNoFieldsAndPassesTheNote() throws SQLException {
        tool.call( w.agent, args( "change", 5, "state", "abandoned", "note", "Superseded." ) );
        verify( w.changes, never() ).update( any(), anyLong(), any(), any(), any() );
        verify( w.changes ).abandon( w.agent, 5L, "Superseded." );
    }

    @Test
    void aNewCommitAndAReviewRequestAreBothDoneInOrder() throws SQLException {
        tool.call( w.agent, args( "change", 5, "head_sha", "def456", "state", "in_review" ) );
        final var order = org.mockito.Mockito.inOrder( w.changes );
        order.verify( w.changes ).update( w.agent, 5L, "def456", null, null );
        order.verify( w.changes ).requestReview( w.agent, 5L );
    }

    @Test
    void sayingNothingIsRefusedByNameBeforeAnythingIsWritten() {
        final var r = tool.call( w.agent, args( "change", 5 ) );
        assertEquals( "change: Say what to change: head_sha, review_url or state.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.changes );
    }

    @Test
    void aStateThatBelongsToAnotherToolIsRefusedByName() {
        for ( final String state : List.of( "approved", "merged", "open", "" ) ) {
            final var r = tool.call( w.agent, args( "change", 5, "head_sha", "x", "state", state ) );
            assertEquals( "state: Use review_change or merge_change.", WorkMcpFixture.text( r ), state );
        }
        verifyNoInteractions( w.changes );
    }

    @Test
    void aNoteWithoutAbandonedIsRefusedByNameSoItIsNeverBelievedSaved() {
        assertEquals( "note: A note goes with state abandoned.", WorkMcpFixture.text(
            tool.call( w.agent, args( "change", 5, "head_sha", "x", "note", "why" ) ) ) );
        assertEquals( "note: A note goes with state abandoned.", WorkMcpFixture.text(
            tool.call( w.agent, args( "change", 5, "state", "in_review", "note", "why" ) ) ) );
        verifyNoInteractions( w.changes );
    }

    @Test
    void anUndeclaredArgumentIsRefusedByNameBeforeAnythingElse() {
        assertEquals( "state_override: Unknown argument.", WorkMcpFixture.text(
            tool.call( w.agent, args( "change", 5, "head_sha", "x", "state_override", "merged" ) ) ) );
        verifyNoInteractions( w.changes );
    }

    @Test
    void aMissingOrNonNumericChangeIsRefusedByName() {
        assertEquals( "change: is required.", WorkMcpFixture.text( tool.call( w.agent, args( "head_sha", "x" ) ) ) );
        assertEquals( "change: must be a whole number.", WorkMcpFixture.text(
            tool.call( w.agent, args( "change", 5.5, "head_sha", "x" ) ) ) );
        assertEquals( "change: must be a whole number.", WorkMcpFixture.text(
            tool.call( w.agent, args( "change", "5", "head_sha", "x" ) ) ) );
    }

    @Test
    void aRefusedWriteNamesNoItemForTheSecurityLogBecauseTheChangeIsNotAnItemKey() {
        final var lines = WriteToolsAuditTest.audited( () -> tool.call( w.agent, args( "change", 5 ) ) );
        assertEquals( 1, lines.size(), lines.toString() );
        assertTrue( lines.get( 0 ).contains( "MCP_WORK_REFUSED tool=update_change item=- agent=builder kind=validation" ),
            lines.get( 0 ) );
    }
}
