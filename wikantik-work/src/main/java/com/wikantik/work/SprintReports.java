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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Assembles a sprint's report from its event history; split from the service for the complexity limit. */
final class SprintReports {

    private final WorkStore store;
    private final ItemViews views;

    SprintReports( final WorkStore store, final ItemViews views ) {
        this.store = store;
        this.views = views;
    }

    /**
     * A planned sprint has no days and nothing added or removed. Otherwise the days run from the start date to the
     * last day, which is the CLOSE DAY (in {@code zone}) of a closed sprint, whether it closed before or after its
     * end date, and TODAY of an active one, also when today is past the end date, so an overdue sprint shows its
     * overrun. The end date never caps the days: otherwise a late-closed sprint's last day would disagree with its
     * completed figures, while {@code added} and {@code removed} (members at the start against members at the close,
     * or now) already include changes made after the end date. An active sprint that starts in the future has no days.
     */
    SprintReport report( final Sprint s, final WorkSprintsService.SprintView view, final SprintHistory h,
                         final LocalDate today, final ZoneId zone ) throws SQLException {
        if ( s.state() == SprintState.PLANNED ) {
            return new SprintReport( view, List.of(), List.of(), List.of() );
        }
        final LocalDate to = s.closedAt() == null ? today : s.closedAt().atZone( zone ).toLocalDate();
        final List< Day > days = Burndown.of( s.id(), h.events(), s.startsOn(), to, h.closedEventId(), zone );
        final Set< Long > start = Burndown.membersAt( s.id(), h.events(), h.startedEventId() );
        final Set< Long > end = Burndown.membersAt( s.id(), h.events(), h.closedEventId() );
        final Set< Long > added = new TreeSet<>( end );
        added.removeAll( start );
        final Set< Long > removed = new TreeSet<>( start );
        removed.removeAll( end );
        return new SprintReport( view, days, summaries( s.projectId(), added ), summaries( s.projectId(), removed ) );
    }

    private List< WorkService.ItemSummary > summaries( final long projectId, final Set< Long > ids )
            throws SQLException {
        // the ids are in order, and an item's id order is its key order within the project
        final List< Item > items = new ArrayList<>();
        for ( final long id : ids ) {
            store.itemById( id ).ifPresent( items::add );
        }
        return views.summarise( projectId, items );
    }
}
