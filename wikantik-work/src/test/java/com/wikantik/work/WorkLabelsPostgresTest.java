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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Type and labels through the service: normalisation, validation, and every edit that must keep them. */
@RequiresPostgres
class WorkLabelsPostgresTest {

    private GateFixture fx;

    @BeforeEach
    void setUp() throws SQLException {
        fx = new GateFixture();
    }

    private Item create( final WorkLevel level, final String assignee, final WorkType type,
                         final List< String > labels ) throws SQLException {
        return fx.svc.createItem( fx.lena, new ItemDraft( "PLAT", level, null,
            level == WorkLevel.EPIC ? null : "wikantik", WorkKind.SOFTWARE, "An item", "", null, null, assignee, null,
            type, labels ) ).item();
    }

    private WorkValidationException refused( final Actor by, final Item i, final String field, final Object value )
            throws SQLException {
        return assertThrows( WorkValidationException.class, () -> fx.set( by, i, field, value ) );
    }

    private long labelEvents( final Item i ) throws SQLException {
        return fx.count( "SELECT count(*) FROM work_event WHERE type = 'field' AND note = 'labels' AND item_id = "
            + i.id() );
    }

    @Test
    void labelsAreNormalisedOnCreateAndOnPatch() throws SQLException {
        final Item i = create( WorkLevel.TASK, null, null, List.of( " UI ", "api", "ui" ) );
        assertEquals( List.of( "api", "ui" ), i.labels() );
        final Item patched = fx.set( fx.lena, i, "labels", List.of( "Zed", "ALPHA" ) );
        assertEquals( List.of( "alpha", "zed" ), patched.labels() );
    }

    @Test
    void reorderingOrRecasingTheSameLabelsWritesNoEvent() throws SQLException {
        final Item i = create( WorkLevel.TASK, null, null, List.of( "billing", "ui" ) );
        final long before = labelEvents( i );
        fx.set( fx.lena, i, "labels", List.of( "UI", "billing" ) );
        assertEquals( before, labelEvents( i ) );
    }

    @Test
    void aBadLabelIsRefusedNamingLabels() throws SQLException {
        final Item i = create( WorkLevel.TASK, null, null, List.of( "ui" ) );
        assertEquals( "labels", refused( fx.lena, i, "labels", List.of( "has space" ) ).field() );
        assertEquals( List.of( "ui" ), fx.fresh( i ).labels() );
    }

    @Test
    void labelsMustBeAList() throws SQLException {
        final Item i = create( WorkLevel.TASK, null, null, null );
        final WorkValidationException e = refused( fx.lena, i, "labels", "ui" );
        assertEquals( "labels", e.field() );
        assertEquals( "labels must be a list of labels.", e.getMessage() );
        assertEquals( "labels", refused( fx.lena, i, "labels", List.of( "ui", 3 ) ).field() );
    }

    @Test
    void typeCannotBeCleared() throws SQLException {
        final Item i = create( WorkLevel.TASK, null, null, null );
        assertEquals( "type", refused( fx.lena, i, "type", null ).field() );
    }

    @Test
    void anUnknownTypeIsRefused() throws SQLException {
        final Item i = create( WorkLevel.TASK, null, null, null );
        final WorkValidationException e = refused( fx.lena, i, "type", "story" );
        assertEquals( "type", e.field() );
        assertEquals( "Unknown type: story", e.getMessage() );
    }

    @Test
    void anEpicIsAlwaysAFeature() throws SQLException {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> create( WorkLevel.EPIC, null, WorkType.BUG, null ) );
        assertEquals( "type", e.field() );
        assertEquals( "An epic is always a feature.", e.getMessage() );
        final Item epic = create( WorkLevel.EPIC, null, null, null );
        assertEquals( WorkType.FEATURE, epic.type() );
        final WorkValidationException patch = refused( fx.lena, epic, "type", "chore" );
        assertEquals( "type", patch.field() );
        assertEquals( "An epic is always a feature.", patch.getMessage() );
    }

    @Test
    void createWithAnAssigneeKeepsTypeAndLabels() throws SQLException {
        final Item i = create( WorkLevel.TASK, "mia", WorkType.BUG, List.of( "ui" ) );
        assertEquals( "mia", i.assignee() );
        assertEquals( WorkType.BUG, fx.fresh( i ).type() );
        assertEquals( List.of( "ui" ), fx.fresh( i ).labels() );
    }

    @Test
    void aTitleOnlyPatchKeepsTypeAndLabels() throws SQLException {
        final Item i = create( WorkLevel.TASK, null, WorkType.CHORE, List.of( "ops" ) );
        final Item renamed = fx.set( fx.lena, i, "title", "Renamed" );
        assertEquals( WorkType.CHORE, renamed.type() );
        assertEquals( List.of( "ops" ), renamed.labels() );
        assertEquals( WorkType.CHORE, fx.fresh( i ).type() );
    }

    @Test
    void aHandoffKeepsTypeAndLabels() throws SQLException {
        final Item i = create( WorkLevel.TASK, null, WorkType.BUG, List.of( "ui" ) );
        fx.set( fx.lena, i, "queue", "implementer" );
        final Item claimed = fx.claimedBy( fx.author, i );
        final Item handed = fx.handoff( fx.author, claimed, WorkStatus.REVIEW, null, "reviewer" );
        assertEquals( "reviewer", handed.queue() );
        assertEquals( WorkType.BUG, handed.type() );
        assertEquals( List.of( "ui" ), handed.labels() );
    }

    @Test
    void anAgentMemberMaySetTypeAndLabels() throws SQLException {
        final Item draft = fx.task();
        final Item claimed = fx.claimedBy( fx.author, draft );
        fx.set( fx.author, claimed, "type", "bug" );
        final Item done = fx.set( fx.author, claimed, "labels", List.of( "api" ) );
        assertEquals( WorkType.BUG, done.type() );
        assertEquals( List.of( "api" ), done.labels() );
        final List< WorkEvent > events = new JdbcWorkStore( fx.ds ).itemEvents( draft.id() ).stream()
            .filter( e -> "field".equals( e.type() )
                && ( "type".equals( e.note() ) || "labels".equals( e.note() ) ) ).toList();
        assertEquals( 2, events.size() );
        assertTrue( events.stream().allMatch( e -> Long.valueOf( fx.author.agentId() ).equals( e.agentId() ) ) );
    }

    @Test
    void anArchivedProjectRefusesALabelChange() throws SQLException {
        final Item i = create( WorkLevel.TASK, null, null, null );
        fx.svc.archiveProject( fx.admin, "PLAT" );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> fx.set( fx.lena, i, "labels", List.of( "ui" ) ) );
        assertEquals( WorkConflictException.ARCHIVED, e.reason() );
    }

    private static ItemFilter byLabel( final Long projectId, final String label ) {
        return new ItemFilter( projectId, null, null, null, null, null, null, null, 0, null, null, null,
            java.util.Set.of( label ) );
    }

    @Test
    void labelsOfListsTheProjectsLabels() throws SQLException {
        create( WorkLevel.TASK, null, null, List.of( "ui", "api" ) );
        create( WorkLevel.TASK, null, null, List.of( "ui" ) );
        assertEquals( List.of( new LabelCount( "ui", 2 ), new LabelCount( "api", 1 ) ),
            fx.svc.features().labelsOf( fx.lena, "PLAT" ) );
        assertThrows( WorkNotFoundException.class,
            () -> fx.svc.features().labelsOf( Actor.human( "stranger", java.util.Set.of(), false ), "PLAT" ) );
    }

    @Test
    void itemsFilteredByLabelStayInTheProject() throws SQLException {
        final Item mine = create( WorkLevel.TASK, null, null, List.of( "ui" ) );
        fx.svc.createProject( fx.admin, "OPS", "Ops", "" );
        final Item theirs = fx.svc.createItem( fx.admin, new ItemDraft( "OPS", WorkLevel.TASK, null, null,
            WorkKind.SOFTWARE, "Ops item", "", null, null, null, null, null, List.of( "ui" ) ) ).item();
        final List< String > summaries = fx.svc.itemSummaries( fx.lena, "PLAT", byLabel( null, "ui" ) ).stream()
            .map( s -> s.item().key() ).toList();
        assertEquals( List.of( mine.key() ), summaries );
        final List< String > items = fx.svc.items( fx.lena, "PLAT", byLabel( theirs.projectId(), "ui" ) ).stream()
            .map( Item::key ).toList();
        assertEquals( List.of( mine.key() ), items );
    }
}
