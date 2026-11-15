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

/** Fixed item statuses. */
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum WorkStatus {
    DRAFT("draft"),
    REFINING("refining"),
    READY("ready"),
    IN_PROGRESS("in_progress"),
    REVIEW("review"),
    DONE("done"),
    CANCELLED("cancelled");

    private final String wire;

    WorkStatus( final String wire ) {
        this.wire = wire;
    }

    /** The lowercase value as stored in the database. */
    public String wire() {
        return wire;
    }

    /** Parses a stored value; throws {@link WorkValidationException} when unknown. */
    public static WorkStatus fromWire( final String value ) {
        for ( final WorkStatus candidate : values() ) {
            if ( candidate.wire.equals( value ) ) {
                return candidate;
            }
        }
        throw new WorkValidationException( "status", "Unknown status: " + value );
    }

    private static final Set<WorkStatus> EPIC_STATUSES = Collections.unmodifiableSet(
            EnumSet.of( DRAFT, IN_PROGRESS, DONE, CANCELLED ) );
    private static final Set<WorkStatus> ALL_STATUSES = Collections.unmodifiableSet( EnumSet.allOf( WorkStatus.class ) );

    /** The statuses an item of {@code level} may hold; epics use only four. */
    public static Set<WorkStatus> allowedFor( final WorkLevel level ) {
        return level == WorkLevel.EPIC ? EPIC_STATUSES : ALL_STATUSES;
    }
}
