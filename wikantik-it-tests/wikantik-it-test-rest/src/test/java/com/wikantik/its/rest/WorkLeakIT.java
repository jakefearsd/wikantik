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
package com.wikantik.its.rest;

import com.google.gson.JsonObject;
import com.wikantik.its.rest.WorkIt.Caller;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work items never reach a wiki surface. An item whose title and body carry a unique token (and a wikilink to a real
 * page) is created; then an ordinary page carrying a second token is created <em>after</em> it. Once that ordinary
 * page shows up on a surface the index has demonstrably caught up, so the item's token being absent from the same
 * surface means something. Checked as the wiki admin, who can see everything a project member can.
 *
 * <p>{@code /api/bundle} has no positive control here: this module runs without an embedder, so a bundle is empty
 * for any query. It is still asserted (an empty answer can only get less empty by a leak).
 */
public class WorkLeakIT {

    private static final String WORK_TOKEN = "zqworkleak7";
    private static final String PAGE_TOKEN = "zqpagecontrol7";
    private static final String TARGET = "WorkLeakTarget";
    private static final String LINKER = "WorkLeakLinker";

    private static void put( final Caller admin, final String page, final String content ) throws Exception {
        final HttpResponse< String > r = admin.send( "PUT", "/api/pages/" + page,
            Map.of( "content", content, "changeNote", "WorkLeakIT" ) );
        assertTrue( r.statusCode() == 200 || r.statusCode() == 201, "save " + page + ": " + r.statusCode() + " " + r.body() );
    }

    private static List< String > surfaces( final String token ) {
        return List.of( "/api/search?q=" + token, "/api/bundle?q=" + token, "/api/changes?since=2000-01-01T00:00:00Z",
            "/sitemap.xml", "/api/pages?limit=1000", "/api/pages?q=" + token, "/api/recent-changes",
            "/api/structure/pages?limit=1000", "/api/backlinks/" + TARGET );
    }

    @Test
    void aWorkItemNeverAppearsOnAWikiSurface() throws Exception {
        final Caller admin = WorkIt.admin();
        put( admin, TARGET, "# Target\n\nA page that work items link to.\n" );

        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", "LEAK", "name", "Leak probe" ) ).statusCode() );
        final HttpResponse< String > item = admin.send( "POST", "/api/work/items", Map.of( "project", "LEAK", "level", "epic",
            "title", "Title " + WORK_TOKEN, "body", "Body " + WORK_TOKEN + " links [[" + TARGET + "]] and [" + TARGET
                + "](" + TARGET + ")." ) );
        assertEquals( 201, item.statusCode(), item.body() );
        final String itemKey = ( ( JsonObject ) WorkIt.json( item ) ).getAsJsonObject( "item" ).get( "key" ).getAsString();
        // the item exists and is readable where it should be
        assertEquals( 200, admin.get( "/api/work/items/" + itemKey ).statusCode() );

        // an ordinary page, created after the item, with its own token and a link to the same target
        put( admin, LINKER, "# Linker\n\nControl " + PAGE_TOKEN + " links to [" + TARGET + "](" + TARGET + ").\n" );

        // positive controls: the ordinary page IS found on the surfaces that index it
        WorkIt.await( "/api/search", admin, "/api/search?q=" + PAGE_TOKEN, b -> b.contains( LINKER ) );
        WorkIt.await( "/sitemap.xml", admin, "/sitemap.xml", b -> b.contains( LINKER ) );
        WorkIt.await( "/api/changes", admin, "/api/changes?since=2000-01-01T00:00:00Z", b -> b.contains( LINKER ) );
        WorkIt.await( "/api/pages", admin, "/api/pages?limit=1000", b -> b.contains( LINKER ) );
        WorkIt.await( "/api/backlinks", admin, "/api/backlinks/" + TARGET, b -> b.contains( LINKER ) );

        // the work item's token and key are on none of them
        for ( final String path : surfaces( WORK_TOKEN ) ) {
            final HttpResponse< String > r = admin.get( path );
            // a surface may repeat the caller's own query back; that is the request, not the item
            final String body = r.body().replaceAll( "\"(?:query|prompt)\"\\s*:\\s*\"[^\"]*\"", "" );
            assertFalse( body.contains( WORK_TOKEN ), path + " leaked the work token: " + r.body() );
            assertFalse( body.contains( itemKey ), path + " leaked the work item key: " + r.body() );
        }
        // the wikilink in the item body adds no backlink: the target's only referrer is the ordinary page
        final String backlinks = admin.get( "/api/backlinks/" + TARGET ).body();
        assertTrue( backlinks.contains( LINKER ), backlinks );
        assertFalse( backlinks.contains( "LEAK" ), backlinks );
    }
}
