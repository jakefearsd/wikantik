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

    /**
     * The events for the differences between two states of one item. A status and an assignee change together,
     * with a non-blank note, collapse into one {@code handoff} event whose values are {@code status|assignee}.
     * A {@code field} event names the field in its note.
     */
    static List< WorkEvent > changeEvents( final Item cur, final Item nxt, final String actor,
                                                   final Actor.Kind kind, final String note ) {
        final List< WorkEvent > out = new ArrayList<>();
        final String blankSafeNote = note == null || note.isBlank() ? null : note;
        if ( !Objects.equals( cur.title(), nxt.title() ) || !Objects.equals( cur.body(), nxt.body() ) ) {
            out.add( event( cur, actor, kind, WorkEvent.EDITED, null, null, null ) );
        }
        final boolean statusChanged = cur.status() != nxt.status();
        final boolean assigneeChanged = !Objects.equals( cur.assignee(), nxt.assignee() );
        if ( statusChanged && assigneeChanged && blankSafeNote != null ) {
            out.add( event( cur, actor, kind, WorkEvent.HANDOFF,
                cur.status().wire() + "|" + Objects.toString( cur.assignee(), "" ),
                nxt.status().wire() + "|" + Objects.toString( nxt.assignee(), "" ), blankSafeNote ) );
        } else {
            if ( statusChanged ) {
                out.add( event( cur, actor, kind, WorkEvent.STATUS, cur.status().wire(), nxt.status().wire(),
                    blankSafeNote ) );
            }
            if ( assigneeChanged ) {
                out.add( event( cur, actor, kind, WorkEvent.ASSIGNED, cur.assignee(), nxt.assignee(), blankSafeNote ) );
            }
        }
        fieldEvent( out, cur, actor, kind, "priority", String.valueOf( cur.priority() ), String.valueOf( nxt.priority() ) );
        fieldEvent( out, cur, actor, kind, "due_on", text( cur.dueOn() ), text( nxt.dueOn() ) );
        fieldEvent( out, cur, actor, kind, "parent_id", text( cur.parentId() ), text( nxt.parentId() ) );
        fieldEvent( out, cur, actor, kind, "product_id", text( cur.productId() ), text( nxt.productId() ) );
        fieldEvent( out, cur, actor, kind, "kind", cur.kind().wire(), nxt.kind().wire() );
        fieldEvent( out, cur, actor, kind, "autonomy", autonomyWire( cur.autonomy() ), autonomyWire( nxt.autonomy() ) );
        fieldEvent( out, cur, actor, kind, "done_means", doneMeansWire( cur.doneMeans() ),
            doneMeansWire( nxt.doneMeans() ) );
        fieldEvent( out, cur, actor, kind, "allow_direct", text( cur.allowDirect() ), text( nxt.allowDirect() ) );
        return out;
    }

        private static void fieldEvent( final List< WorkEvent > out, final Item cur, final String actor,
                                    final Actor.Kind kind, final String field, final String from, final String to ) {
        if ( !Objects.equals( from, to ) ) {
            out.add( event( cur, actor, kind, WorkEvent.FIELD, from, to, field ) );
        }
    }

    private static WorkEvent event( final Item item, final String actor, final Actor.Kind kind, final String type,
                                    final String from, final String to, final String note ) {
        return new WorkEvent( 0, item.projectId(), item.id(), item.key(), null, actor, kind, null, type, from, to, note );
    }
}
