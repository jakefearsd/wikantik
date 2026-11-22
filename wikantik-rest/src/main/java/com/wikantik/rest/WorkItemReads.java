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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.wikantik.work.FindFilter;
import com.wikantik.work.FindPage;
import com.wikantik.work.FindParams;
import com.wikantik.work.WorkService;

import java.util.List;

/** The read routes of {@link WorkItemResource} that carry the outside-reporter marker (see {@link WorkOutside}). */
final class WorkItemReads {

    private WorkItemReads() {
    }

    /** One project's list (as ever) with {@code project}; without it, a find across the projects the caller can see. */
    static void list( final WorkResourceBase servlet, final WorkResourceBase.Call c ) throws Exception {
        final String project = c.query( "project" );
        if ( project != null ) {
            WorkFilters.refuseFindOnly( c );
            final List< WorkService.ItemSummary > summaries = c.svc.itemSummaries( c.actor, project, servlet.filter( c, true ) );
            c.tree( WorkOutside.markCards( servlet.summariesTree( summaries, c.actor ).getAsJsonArray(), summaries,
                WorkOutside.of( c, project, WorkOutside.itemsOf( summaries ) ) ) );
            return;
        }
        WorkFilters.refuseListOnly( c );
        final FindFilter wanted = FindParams.parse( WorkFilters.findParams( c ) );
        final FindPage page = c.svc.features().find().find( c.actor, wanted );
        c.tree( WorkFindJson.page( servlet.summariesTree( page.items(), c.actor ), page.next() ) );
    }

    /** An item view with {@code reportedFromOutside} (see {@link WorkOutside}). */
    static JsonObject markedView( final WorkResourceBase servlet, final WorkResourceBase.Call c, final WorkService.ItemView view ) {
        return WorkOutside.markView( servlet.toTree( view, c.actor ).getAsJsonObject(), view.item(),
            WorkOutside.of( c, view.projectKey(), List.of( view.item() ) ) );
    }
}
