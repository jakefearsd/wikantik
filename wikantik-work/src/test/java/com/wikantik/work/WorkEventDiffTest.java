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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every change to an item leaves a {@code work_event} (the module's audit rule): one event per changed field, with
 * the field in its note and the old and new value as text. Pure; no storage.
 */
class WorkEventDiffTest {

    private static final Actor BY = Actor.human( "alice", java.util.Set.of(), false );

    private static Item base() {
        return new Item( 5, 2, "PLAT-5", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "title", "body", WorkStatus.READY,
            3, 1.0, null, "alice", "alice", null, null, null, 1, Instant.EPOCH, Instant.EPOCH, null, null, null, null,
            null, null, WorkType.FEATURE, List.of(), null, null );
    }

    private static Item with( final UnaryOperator< Builder > f ) {
        return f.apply( new Builder( base() ) ).build();
    }

    /** A copy of an item with one field replaced. */
    private record Builder( Item i ) {
        Item build() {
            return i;
        }

        Builder parent( final Long v ) {
            return new Builder( new Item( i.id(), i.projectId(), i.key(), i.level(), v, i.productId(), i.kind(), i.title(),
                i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
                i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(),
                i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(),
                i.sprintId(), i.estimate() ) );
        }

        Builder product( final Long v ) {
            return new Builder( new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), v, i.kind(), i.title(),
                i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
                i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(),
                i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(),
                i.sprintId(), i.estimate() ) );
        }

        Builder kind( final WorkKind v ) {
            return new Builder( new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), v,
                i.title(), i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(),
                i.autonomy(), i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(),
                i.queue(), i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(),
                i.labels(), i.sprintId(), i.estimate() ) );
        }

        Builder due( final LocalDate v ) {
            return new Builder( new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
                i.title(), i.body(), i.status(), i.priority(), i.rank(), v, i.assignee(), i.reporter(), i.autonomy(),
                i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(),
                i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(),
                i.sprintId(), i.estimate() ) );
        }

        Builder autonomy( final Autonomy v ) {
            return new Builder( new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
                i.title(), i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), v,
                i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(),
                i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(),
                i.sprintId(), i.estimate() ) );
        }

        Builder doneMeans( final DoneMeans v ) {
            return new Builder( new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
                i.title(), i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(),
                i.autonomy(), v, i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(),
                i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(),
                i.sprintId(), i.estimate() ) );
        }

        Builder allowDirect( final Boolean v ) {
            return new Builder( new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
                i.title(), i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(),
                i.autonomy(), i.doneMeans(), v, i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(),
                i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(),
                i.sprintId(), i.estimate() ) );
        }

        Builder text( final String title, final String body ) {
            return new Builder( new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
                title, body, i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
                i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(),
                i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(),
                i.sprintId(), i.estimate() ) );
        }

        Builder statusAndAssignee( final WorkStatus status, final String assignee, final String queue ) {
            return new Builder( new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(),
                i.title(), i.body(), status, i.priority(), i.rank(), i.dueOn(), assignee, i.reporter(), i.autonomy(),
                i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), queue,
                i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(),
                i.sprintId(), i.estimate() ) );
        }
    }

    static Stream< Arguments > fieldChanges() {
        return Stream.of(
            Arguments.of( "due_on", null, "2026-12-01", (UnaryOperator< Builder >) b -> b.due( LocalDate.parse( "2026-12-01" ) ) ),
            Arguments.of( "parent_id", null, "10", (UnaryOperator< Builder >) b -> b.parent( 10L ) ),
            Arguments.of( "product_id", null, "3", (UnaryOperator< Builder >) b -> b.product( 3L ) ),
            Arguments.of( "kind", "software", "docs", (UnaryOperator< Builder >) b -> b.kind( WorkKind.DOCS ) ),
            Arguments.of( "autonomy", null, "autonomous", (UnaryOperator< Builder >) b -> b.autonomy( Autonomy.AUTONOMOUS ) ),
            Arguments.of( "done_means", null, "in_production", (UnaryOperator< Builder >) b -> b.doneMeans( DoneMeans.IN_PRODUCTION ) ),
            Arguments.of( "allow_direct", null, "true", (UnaryOperator< Builder >) b -> b.allowDirect( true ) ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fieldChanges" )
    void aChangedFieldLeavesOneFieldEventNamingItWithBothValues( final String field, final String from, final String to,
                                                                 final UnaryOperator< Builder > change ) {
        final List< WorkEvent > events = WorkEventDiff.changeEvents( base(), with( change ), BY, null, false );
        assertEquals( 1, events.size(), field );
        final WorkEvent e = events.get( 0 );
        assertEquals( WorkEvent.FIELD, e.type() );
        assertEquals( field, e.note() );
        assertEquals( from, e.fromValue() );
        assertEquals( to, e.toValue() );
        assertEquals( "PLAT-5", e.itemKey() );
        assertEquals( "alice", e.actor() );
    }

    @ParameterizedTest( name = "{0} back to unset" )
    @MethodSource( "fieldChanges" )
    void clearingAFieldIsRecordedToo( final String field, final String from, final String to,
                                      final UnaryOperator< Builder > change ) {
        final List< WorkEvent > events = WorkEventDiff.changeEvents( with( change ), base(), BY, null, false );
        assertEquals( 1, events.size(), field );
        assertEquals( field, events.get( 0 ).note() );
        assertEquals( to, events.get( 0 ).fromValue() );
        assertEquals( from, events.get( 0 ).toValue() );
    }

    @Test
    void anUnchangedItemLeavesNoEvent() {
        assertTrue( WorkEventDiff.changeEvents( base(), base(), BY, "a note", false ).isEmpty() );
    }

    @Test
    void aChangedTitleOrBodyIsOneEditedEventAndNothingElse() {
        assertEquals( List.of( WorkEvent.EDITED ), WorkEventDiff.changeEvents( base(), with( b -> b.text( "new", "body" ) ),
            BY, null, false ).stream().map( WorkEvent::type ).toList() );
        assertEquals( List.of( WorkEvent.EDITED ), WorkEventDiff.changeEvents( base(), with( b -> b.text( "title", "new" ) ),
            BY, null, false ).stream().map( WorkEvent::type ).toList() );
        assertEquals( List.of( WorkEvent.EDITED ), WorkEventDiff.changeEvents( base(), with( b -> b.text( "title", null ) ),
            BY, null, false ).stream().map( WorkEvent::type ).toList() );
    }

    @Test
    void aStatusChangeAndAnAssignmentChangeAreTwoEventsUnlessANoteJoinsThemIntoAHandoff() {
        final Item moved = with( b -> b.statusAndAssignee( WorkStatus.IN_PROGRESS, "bob", null ) );
        assertEquals( List.of( WorkEvent.STATUS, WorkEvent.ASSIGNED ),
            WorkEventDiff.changeEvents( base(), moved, BY, null, false ).stream().map( WorkEvent::type ).toList() );
        assertEquals( List.of( WorkEvent.STATUS, WorkEvent.ASSIGNED ),
            WorkEventDiff.changeEvents( base(), moved, BY, "   ", false ).stream().map( WorkEvent::type ).toList(),
            "a blank note is no note" );
        final List< WorkEvent > handoff = WorkEventDiff.changeEvents( base(), moved, BY, "over to you", false );
        assertEquals( 1, handoff.size() );
        assertEquals( WorkEvent.HANDOFF, handoff.get( 0 ).type() );
        assertEquals( "ready|alice", handoff.get( 0 ).fromValue() );
        assertEquals( "in_progress|bob", handoff.get( 0 ).toValue() );
        assertEquals( "over to you", handoff.get( 0 ).note() );
    }

    @Test
    void aNoteAloneOrAStatusAloneNeverMakesAHandoffButTheFlagForcesOne() {
        final Item statusOnly = with( b -> b.statusAndAssignee( WorkStatus.IN_PROGRESS, "alice", null ) );
        assertEquals( List.of( WorkEvent.STATUS ),
            WorkEventDiff.changeEvents( base(), statusOnly, BY, "n", false ).stream().map( WorkEvent::type ).toList() );
        assertEquals( "n", WorkEventDiff.changeEvents( base(), statusOnly, BY, "n", false ).get( 0 ).note() );
        final Item assignOnly = with( b -> b.statusAndAssignee( WorkStatus.READY, "bob", null ) );
        assertEquals( List.of( WorkEvent.ASSIGNED ),
            WorkEventDiff.changeEvents( base(), assignOnly, BY, "n", false ).stream().map( WorkEvent::type ).toList() );
        final List< WorkEvent > forced = WorkEventDiff.changeEvents( base(), base(), BY, null, true );
        assertEquals( List.of( WorkEvent.HANDOFF ), forced.stream().map( WorkEvent::type ).toList() );
        assertEquals( "ready|alice", forced.get( 0 ).fromValue() );
        assertEquals( "ready|alice", forced.get( 0 ).toValue() );
    }

    @Test
    void anAssignmentToAQueueIsRecordedAsQueueAndKey() {
        final Item queued = with( b -> b.statusAndAssignee( WorkStatus.READY, null, "implementer" ) );
        final WorkEvent e = WorkEventDiff.changeEvents( base(), queued, BY, null, false ).get( 0 );
        assertEquals( WorkEvent.ASSIGNED, e.type() );
        assertEquals( "alice", e.fromValue() );
        assertEquals( "queue:implementer", e.toValue() );
    }

    @Test
    void aReleaseSaysWhetherTheItemWasReassignedOrOnlyMoved() {
        final Item moved = with( b -> b.statusAndAssignee( WorkStatus.DONE, "alice", null ) );
        final WorkEvent plain = WorkEventDiff.releasedEvent( base(), moved, BY, 9 );
        assertEquals( WorkEvent.RELEASED, plain.type() );
        assertEquals( "agent:9", plain.fromValue() );
        assertEquals( "done", plain.toValue() );
        assertEquals( "moved", plain.note() );
        assertEquals( "reassigned", WorkEventDiff.releasedEvent( base(),
            with( b -> b.statusAndAssignee( WorkStatus.READY, "bob", null ) ), BY, 9 ).note() );
        assertEquals( "reassigned", WorkEventDiff.releasedEvent( base(),
            with( b -> b.statusAndAssignee( WorkStatus.READY, "alice", "ops" ) ), BY, 9 ).note(), "a queue counts too" );
    }

    @Test
    void estimatesAreComparedByValueNotScale() {
        final Item three = new Item( 5, 2, "PLAT-5", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "title", "body",
            WorkStatus.READY, 3, 1.0, null, "alice", "alice", null, null, null, 1, Instant.EPOCH, Instant.EPOCH, null, null,
            null, null, null, null, WorkType.FEATURE, List.of(), null, new BigDecimal( "3.0" ) );
        final Item threeAgain = new Item( 5, 2, "PLAT-5", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "title", "body",
            WorkStatus.READY, 3, 1.0, null, "alice", "alice", null, null, null, 1, Instant.EPOCH, Instant.EPOCH, null, null,
            null, null, null, null, WorkType.FEATURE, List.of(), null, new BigDecimal( "3" ) );
        assertTrue( WorkEventDiff.changeEvents( three, threeAgain, BY, null, false ).isEmpty() );
        final WorkEvent e = WorkEventDiff.changeEvents( base(), three, BY, null, false ).get( 0 );
        assertEquals( "estimate", e.note() );
        assertEquals( "3", e.toValue() );
    }
}
