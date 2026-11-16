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

import java.sql.SQLException;
import java.util.Map;

/**
 * Where an item is assigned: a person who belongs to the project or a queue that exists and is active, never both.
 * The assignment rules {@link ItemPatchApplier} applies, kept apart so each stays small.
 */
final class ItemTargets {

    static final String ONE_TARGET = "Assign a person or a queue, not both.";
    static final String NO_QUEUE = "No such queue.";

    /** Whether a login holds a role on a project; the service resolves it through the directory's groups. */
    @FunctionalInterface
    interface MemberCheck {
        boolean isMember( long projectId, String login ) throws SQLException;
    }

    private final WorkStore store;
    private final MemberCheck members;

    ItemTargets( final WorkStore store, final MemberCheck members ) {
        this.store = store;
        this.members = members;
    }

    /** A new item: one target at most, a queue that is active, an assignee who belongs to the project. */
    void checkNew( final Project project, final Item next ) throws SQLException {
        JdbcWorkCatalog.requireOneTarget( next );
        if ( next.queue() != null ) {
            requireActiveQueue( next.queue() );
        }
        checkAssignee( project, null, next.assignee() );
    }

    /**
     * The queue the item ends up on. Naming a queue takes the item off its person and naming a person takes it off
     * its queue: an item has one assignment target, so a patch naming both is refused. A named queue must exist and
     * be active (one message for both, resolved only after the caller's project scope has been checked).
     */
    String changedQueue( final Item current, final Map< String, Object > changes ) throws SQLException {
        final String named = changes.containsKey( "queue" )
            ? PatchValues.blankToNull( PatchValues.string( "queue", changes.get( "queue" ) ) ) : null;
        if ( named != null && PatchValues.assignee( changes.get( "assignee" ) ) != null ) {
            throw new WorkValidationException( "queue", ONE_TARGET );
        }
        if ( named != null && !named.equals( current.queue() ) ) {
            requireActiveQueue( named );
        }
        if ( changes.containsKey( "queue" ) ) {
            return named;
        }
        return changes.containsKey( "assignee" ) && PatchValues.assignee( changes.get( "assignee" ) ) != null
            ? null : current.queue();
    }

    /** The queue must exist and not be retired; unknown and retired are the same refusal. */
    void requireActiveQueue( final String key ) throws SQLException {
        final Queue q = store.queue( key ).orElse( null );
        if ( q == null || q.retiredAt() != null ) {
            throw new WorkValidationException( "queue", NO_QUEUE );
        }
    }

    void checkAssignee( final Project project, final String before, final String after ) throws SQLException {
        if ( after != null && !after.equals( before ) && !members.isMember( project.id(), after ) ) {
            throw new WorkValidationException( "assignee", "The assignee is not a member of this project." );
        }
    }
}
