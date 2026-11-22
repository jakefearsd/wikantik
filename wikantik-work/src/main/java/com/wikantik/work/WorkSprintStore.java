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

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Sprints. Reached through {@link WorkStore#sprints()}. */
public interface WorkSprintStore {

    /** The sprint with this id, whatever its project; empty when unknown. */
    Optional< Sprint > byId( long id ) throws SQLException;

    /** The project's sprints: the active one, then planned by start date, then closed by start date, newest first. */
    List< Sprint > forProject( long projectId ) throws SQLException;

    /** What each of the project's sprints holds now, keyed by sprint id (a sprint with no items has no entry). */
    Map< Long, SprintTotals > totals( long projectId ) throws SQLException;

    /** The project's closed sprints, newest close first, at most {@code limit}. */
    List< Sprint > closed( long projectId, int limit ) throws SQLException;

    /** Creates a planned sprint and writes {@code sprint_created}; a taken name is a validation error on {@code name}. */
    Sprint create( Project project, SprintDraft d, Actor by ) throws SQLException;

    /**
     * Edits name, goal and dates. A closed sprint is a conflict {@code sprint_closed}; a version mismatch is
     * {@code stale}; an edit that changes nothing returns the row and writes nothing.
     */
    Sprint update( long id, int expectedVersion, SprintDraft d, Actor by ) throws SQLException;

    /**
     * Starts a planned sprint, recording the commitment from the items now in it. A second active sprint in the
     * project is a conflict {@code sprint_active}, also when two starts race.
     */
    Sprint start( long id, int expectedVersion, Actor by ) throws SQLException;

    /**
     * Closes an active sprint, recording the completion and moving its unfinished work to the planned sprint
     * {@code moveOpenToSprintId} of the same project, or to the backlog when that is null. All or nothing.
     */
    Sprint close( long id, int expectedVersion, Long moveOpenToSprintId, Actor by ) throws SQLException;

    /** The events a report replays for every item ever in the sprint, with the start and close marker ids. */
    SprintHistory eventsFor( long projectId, long sprintId ) throws SQLException;

    /** An item's points through its children (P2-7): stories' own estimates, else their tasks'; cancelled excluded. */
    BigDecimal childPoints( long itemId ) throws SQLException;
}
