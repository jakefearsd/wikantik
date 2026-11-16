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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.wikantik.work.SqlValues.autonomyWire;
import static com.wikantik.work.SqlValues.doneMeansWire;
import static com.wikantik.work.SqlValues.text;

/** The events that describe the differences between two states of one item. Pure: no storage. */
final class WorkEventDiff {

    private WorkEventDiff() {
    }

    /** The assignment as event text: a login as is, a queue as {@code queue:<key>}, none as null. */
    static String assignmentText( final Item item ) {
        return item.queue() != null ? "queue:" + item.queue() : item.assignee();
    }

    /**
     * The events for the differences between two states of one item. A status and an assignment change
     * together ({@code (assignee, queue)} is one assignment), with a non-blank note, collapse into one
     * {@code handoff} event whose values are {@code status|target}; {@code asHandoff} forces that one event
     * whatever changed. A {@code field} event names the field in its note.
     */
    static List< WorkEvent > changeEvents( final Item cur, final Item nxt, final Actor by, final String note,
                                           final boolean asHandoff ) {
        final List< WorkEvent > out = new ArrayList<>();
        final String blankSafeNote = note == null || note.isBlank() ? null : note;
        if ( !Objects.equals( cur.title(), nxt.title() ) || !Objects.equals( cur.body(), nxt.body() ) ) {
            out.add( event( cur, by, WorkEvent.EDITED, null, null, null ) );
        }
        final String from = assignmentText( cur );
        final String to = assignmentText( nxt );
        final boolean statusChanged = cur.status() != nxt.status();
        final boolean assigneeChanged = !Objects.equals( from, to );
        if ( asHandoff || statusChanged && assigneeChanged && blankSafeNote != null ) {
            out.add( event( cur, by, WorkEvent.HANDOFF, cur.status().wire() + "|" + Objects.toString( from, "" ),
                nxt.status().wire() + "|" + Objects.toString( to, "" ), blankSafeNote ) );
        } else {
            if ( statusChanged ) {
                out.add( event( cur, by, WorkEvent.STATUS, cur.status().wire(), nxt.status().wire(), blankSafeNote ) );
            }
            if ( assigneeChanged ) {
                out.add( event( cur, by, WorkEvent.ASSIGNED, from, to, blankSafeNote ) );
            }
        }
        fieldEvent( out, cur, by, "priority", String.valueOf( cur.priority() ), String.valueOf( nxt.priority() ) );
        fieldEvent( out, cur, by, "due_on", text( cur.dueOn() ), text( nxt.dueOn() ) );
        fieldEvent( out, cur, by, "parent_id", text( cur.parentId() ), text( nxt.parentId() ) );
        fieldEvent( out, cur, by, "product_id", text( cur.productId() ), text( nxt.productId() ) );
        fieldEvent( out, cur, by, "kind", cur.kind().wire(), nxt.kind().wire() );
        fieldEvent( out, cur, by, "autonomy", autonomyWire( cur.autonomy() ), autonomyWire( nxt.autonomy() ) );
        fieldEvent( out, cur, by, "done_means", doneMeansWire( cur.doneMeans() ), doneMeansWire( nxt.doneMeans() ) );
        fieldEvent( out, cur, by, "allow_direct", text( cur.allowDirect() ), text( nxt.allowDirect() ) );
        return out;
    }

    /**
     * The {@code released} event for a claim a change ends: the previous holder as {@code agent:<id>} in
     * from, the note {@code reassigned} when the assignee or queue changed (even with a status change), else
     * {@code moved}. The actor is whoever made the change.
     */
    static WorkEvent releasedEvent( final Item cur, final Item nxt, final Actor by ) {
        final boolean reassigned = !Objects.equals( cur.assignee(), nxt.assignee() )
            || !Objects.equals( cur.queue(), nxt.queue() );
        return event( cur, by, WorkEvent.RELEASED, "agent:" + cur.claimedByAgent(), null,
            reassigned ? "reassigned" : "moved" );
    }

    private static void fieldEvent( final List< WorkEvent > out, final Item cur, final Actor by, final String field,
                                    final String from, final String to ) {
        if ( !Objects.equals( from, to ) ) {
            out.add( event( cur, by, WorkEvent.FIELD, from, to, field ) );
        }
    }

    private static WorkEvent event( final Item item, final Actor by, final String type, final String from,
                                    final String to, final String note ) {
        return new WorkEvent( 0, item.projectId(), item.id(), item.key(), null, by.login(), by.kind(), by.agentId(),
            type, from, to, note );
    }
}
