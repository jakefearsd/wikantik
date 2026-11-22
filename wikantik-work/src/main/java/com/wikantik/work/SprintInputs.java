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

import java.time.LocalDate;

/** Normalises and validates the fields of a sprint a caller supplied (P2-9). */
final class SprintInputs {

    static final int NAME_MAX = 60;
    static final int GOAL_MAX = 500;

    private SprintInputs() {
    }

    /** The draft with a trimmed name and a non-null trimmed goal; a refusal names the offending field. */
    static SprintDraft normalise( final SprintDraft d ) {
        final String name = d == null || d.name() == null ? "" : d.name().trim();
        if ( name.isEmpty() || name.length() > NAME_MAX ) {
            throw new WorkValidationException( "name", "A sprint needs a name of at most " + NAME_MAX + " characters." );
        }
        final String goal = d.goal() == null ? "" : d.goal().trim();
        if ( goal.length() > GOAL_MAX ) {
            throw new WorkValidationException( "goal", "A goal is at most " + GOAL_MAX + " characters." );
        }
        requireDates( d.startsOn(), d.endsOn() );
        return new SprintDraft( name, goal, d.startsOn(), d.endsOn() );
    }

    private static void requireDates( final LocalDate starts, final LocalDate ends ) {
        if ( starts == null || ends == null ) {
            throw new WorkValidationException( starts == null ? "startsOn" : "endsOn",
                "A sprint needs a start and an end date." );
        }
        if ( ends.isBefore( starts ) ) {
            throw new WorkValidationException( "endsOn", "A sprint ends on or after the day it starts." );
        }
    }
}
