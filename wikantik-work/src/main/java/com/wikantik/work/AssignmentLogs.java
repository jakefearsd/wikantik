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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The rules of {@link WorkService#assignmentLog}: which projects a query covers, and the rows it returns. */
final class AssignmentLogs {

    private AssignmentLogs() {
    }

    /** Refuses an unknown type before anything is looked up. */
    static void validate( final AssignmentLogQuery query ) {
        WorkInputs.requireEventTypes( query.types(), WorkEvent.ASSIGNMENT_LOG_TYPES );
    }

    /**
     * The log over {@code visible} (what the caller can see, nothing else): all of them, or the one named by the
     * query's project key. A key that matches none of them is not found, as a project that does not exist is.
     */
    static List< AssignmentLogEvent > read( final WorkStore store, final AssignmentLogQuery query,
                                            final List< Project > visible ) throws SQLException {
        final boolean one = query.projectKey() != null && !query.projectKey().isBlank();
        final Map< Long, Project > covered = new LinkedHashMap<>();
        for ( final Project p : visible ) {
            if ( !one || p.key().equalsIgnoreCase( query.projectKey() ) ) {
                covered.put( p.id(), p );
            }
        }
        if ( one && covered.isEmpty() ) {
            throw new WorkNotFoundException( WorkScopes.NOT_FOUND );
        }
        final List< AssignmentLogEvent > out = new ArrayList<>();
        for ( final LogEntry e : store.eventsAcross( covered.keySet(), query.before(), query.beforeId(), query.types(),
                query.actorKind(), query.agentId(), WorkInputs.activityLimit( query.limit() ) ) ) {
            out.add( AssignmentLogEvent.of( e, covered.get( e.event().projectId() ) ) );
        }
        return out;
    }
}
