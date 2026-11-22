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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.work.Day;
import com.wikantik.work.Sprint;
import com.wikantik.work.SprintReport;
import com.wikantik.work.Velocity;
import com.wikantik.work.WorkSprintsService.SprintView;

import java.math.BigDecimal;
import java.util.List;

/** The wire form of a sprint. No login appears in it: a sprint holds none. */
final class WorkSprintJson {

    private WorkSprintJson() {
    }

    /** The sprint, its commitment and completion (null until they are fixed), and what it holds now. */
    static JsonObject view( final SprintView v ) {
        final Sprint s = v.sprint();
        final JsonObject o = new JsonObject();
        o.addProperty( "id", s.id() );
        o.addProperty( "project", s.projectKey() );
        o.addProperty( "name", s.name() );
        o.addProperty( "goal", s.goal() );
        o.addProperty( "startsOn", s.startsOn().toString() );
        o.addProperty( "endsOn", s.endsOn().toString() );
        o.addProperty( "state", s.state().wire() );
        o.addProperty( "startedAt", s.startedAt() == null ? null : s.startedAt().toString() );
        o.addProperty( "closedAt", s.closedAt() == null ? null : s.closedAt().toString() );
        o.addProperty( "version", s.version() );
        o.add( "committed", totals( s.committedItems(), s.committedPoints() ) );
        o.add( "completed", totals( s.completedItems(), s.completedPoints() ) );
        final JsonObject current = new JsonObject();
        current.addProperty( "items", v.items() );
        current.addProperty( "points", v.points() );
        current.addProperty( "doneItems", v.doneItems() );
        current.addProperty( "donePoints", v.donePoints() );
        o.add( "current", current );
        return o;
    }

    private static com.google.gson.JsonElement totals( final Integer items, final BigDecimal points ) {
        if ( items == null ) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        final JsonObject t = new JsonObject();
        t.addProperty( "items", items );
        t.addProperty( "points", points );
        return t;
    }

    /**
     * {@code {sprint, days:[{date, remainingPoints, remainingItems, scopePoints, scopeItems}]}}; the caller adds
     * {@code added} and {@code removed}, which are item summaries rendered for the viewer.
     */
    static JsonObject report( final SprintReport report ) {
        final JsonArray days = new JsonArray();
        for ( final Day d : report.days() ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "date", d.date().toString() );
            o.addProperty( "remainingPoints", d.remainingPoints() );
            o.addProperty( "remainingItems", d.remainingItems() );
            o.addProperty( "scopePoints", d.scopePoints() );
            o.addProperty( "scopeItems", d.scopeItems() );
            days.add( o );
        }
        final JsonObject out = new JsonObject();
        out.add( "sprint", view( report.sprint() ) );
        out.add( "days", days );
        return out;
    }

    /** The project's sprints, in the order the service gives them. */
    static JsonArray list( final List< SprintView > views ) {
        final JsonArray out = new JsonArray();
        for ( final SprintView v : views ) {
            out.add( view( v ) );
        }
        return out;
    }

    /** {@code {sprints:[{id, name, endsOn, committedItems, committedPoints, completedItems, completedPoints}]}}. */
    static JsonObject velocity( final List< Velocity > velocity ) {
        final JsonArray rows = new JsonArray();
        for ( final Velocity v : velocity ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "id", v.sprintId() );
            o.addProperty( "name", v.name() );
            o.addProperty( "endsOn", v.endsOn().toString() );
            o.addProperty( "committedItems", v.committedItems() );
            o.addProperty( "committedPoints", v.committedPoints() );
            o.addProperty( "completedItems", v.completedItems() );
            o.addProperty( "completedPoints", v.completedPoints() );
            rows.add( o );
        }
        final JsonObject out = new JsonObject();
        out.add( "sprints", rows );
        return out;
    }
}
