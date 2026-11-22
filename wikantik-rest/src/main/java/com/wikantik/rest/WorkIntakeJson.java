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
import com.wikantik.work.Actor;
import com.wikantik.work.ReportableProject;
import com.wikantik.work.ReportedComment;
import com.wikantik.work.ReportedEvent;
import com.wikantik.work.ReportedItem;
import com.wikantik.work.ReportedItemView;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The wire form of intake, written field by field from the service's reduced records.
 *
 * <p><b>Why by hand.</b> What a reporter may read is decided by which components those records have. If this class
 * serialised a record generically (or passed it through {@code WorkJson.enrich}), a component added to a record later
 * would reach every reporter without anyone choosing that. Here a field reaches the wire only when it is named in this
 * file; {@code WorkIntakeJsonTest} pins the exact key sets.
 *
 * <p><b>Names.</b> The records already carry display names resolved in the service, so nothing here maps a login: the
 * {@code …Name} twins ({@code reporterName}, {@code authorName}, {@code actorName}) repeat the same display name so
 * the shape matches what the work UI reads for members. {@code mine} on a comment says it is the viewer's own, by
 * display name (unique among people).
 *
 * <p><b>Nulls.</b> Every field is always written; an absent value (a comment never edited, an item with no body) is a
 * JSON null, as everywhere in the work API.
 */
final class WorkIntakeJson {

    private WorkIntakeJson() {
    }

    /** {@code {key, name, role}}: nothing else of a project (no description, no autonomy). */
    static JsonObject reportable( final ReportableProject p ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "key", p.key() );
        o.addProperty( "name", p.name() );
        o.addProperty( "role", p.role() == null ? null : p.role().wire() );
        return o;
    }

    static JsonArray reportable( final List< ReportableProject > projects ) {
        final JsonArray out = new JsonArray();
        projects.forEach( p -> out.add( reportable( p ) ) );
        return out;
    }

    /** The list row of an item the caller filed. */
    static JsonObject listed( final ReportedItem i ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "key", i.key() );
        o.addProperty( "projectKey", i.projectKey() );
        o.addProperty( "projectName", i.projectName() );
        o.addProperty( "level", i.level() == null ? null : i.level().wire() );
        o.addProperty( "type", i.type() == null ? null : i.type().wire() );
        o.addProperty( "status", i.status() == null ? null : i.status().wire() );
        o.addProperty( "title", i.title() );
        o.addProperty( "createdAt", text( i.createdAt() ) );
        o.addProperty( "updatedAt", text( i.updatedAt() ) );
        o.addProperty( "commentCount", i.commentCount() );
        return o;
    }

    static JsonArray listed( final List< ReportedItem > items ) {
        final JsonArray out = new JsonArray();
        items.forEach( i -> out.add( listed( i ) ) );
        return out;
    }

    /** The reporter's view of one item; {@code viewerName} is the caller's display name (for {@code mine}). */
    static JsonObject view( final ReportedItemView v, final String viewerName ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "key", v.key() );
        o.addProperty( "projectKey", v.projectKey() );
        o.addProperty( "projectName", v.projectName() );
        o.addProperty( "level", v.level() == null ? null : v.level().wire() );
        o.addProperty( "type", v.type() == null ? null : v.type().wire() );
        o.addProperty( "status", v.status() == null ? null : v.status().wire() );
        o.addProperty( "title", v.title() );
        o.addProperty( "body", v.body() );
        o.addProperty( "bodyHtml", v.bodyHtml() );
        o.addProperty( "reporter", v.reporter() );
        o.addProperty( "reporterName", v.reporter() );
        o.addProperty( "createdAt", text( v.createdAt() ) );
        o.addProperty( "updatedAt", text( v.updatedAt() ) );
        final JsonArray comments = new JsonArray();
        v.comments().forEach( c -> comments.add( comment( c, viewerName ) ) );
        o.add( "comments", comments );
        final JsonArray events = new JsonArray();
        v.events().forEach( e -> events.add( event( e ) ) );
        o.add( "events", events );
        o.addProperty( "archived", v.archived() );
        return o;
    }

    static JsonObject comment( final ReportedComment c, final String viewerName ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "id", c.id() );
        o.addProperty( "author", c.author() );
        o.addProperty( "authorName", c.author() );
        o.addProperty( "authorKind", kind( c.authorKind() ) );
        o.addProperty( "body", c.body() );
        o.addProperty( "bodyHtml", c.bodyHtml() );
        final JsonArray mentions = new JsonArray();
        if ( c.mentions() != null ) {
            c.mentions().forEach( mentions::add );
        }
        o.add( "mentions", mentions );
        o.addProperty( "createdAt", text( c.createdAt() ) );
        o.addProperty( "editedAt", text( c.editedAt() ) );
        o.addProperty( "removedAt", text( c.removedAt() ) );
        o.addProperty( "mine", viewerName != null && c.authorKind() == Actor.Kind.HUMAN && viewerName.equals( c.author() ) );
        return o;
    }

    static JsonObject event( final ReportedEvent e ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "id", e.id() );
        o.addProperty( "type", e.type() );
        o.addProperty( "at", text( e.at() ) );
        o.addProperty( "actor", e.actor() );
        o.addProperty( "actorName", e.actor() );
        o.addProperty( "actorKind", kind( e.actorKind() ) );
        o.addProperty( "fromValue", e.fromValue() );
        o.addProperty( "toValue", e.toValue() );
        return o;
    }

    private static String text( final Instant at ) {
        return at == null ? null : at.toString();
    }

    private static String kind( final Actor.Kind k ) {
        return k == null ? null : k.name().toLowerCase( Locale.ROOT );
    }
}
