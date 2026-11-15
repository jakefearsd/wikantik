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
 * Work items never reach a wiki surface. An item whose title and body carry a shared word and a word of its own (and
 * a wikilink to a real page) is created; then an ordinary page carrying the shared word is created <em>after</em> it.
 * On every surface listed below the ordinary page is first waited for (a 200 answer that shows it: the positive
 * control, so the index has demonstrably caught up and the path is right), and only then is the item's own word, its
 * key and its title asserted absent from that same answer. The surfaces are queried by the shared word, which appears
 * in both, so a surface that echoes the request back echoes something that is not the item's own text.
 * Checked as the wiki admin, who can see everything a project member can.
 *
 * <h2>Not covered here</h2>
 * <ul>
 *   <li>{@code /api/pages?q=}: it filters by page name, not text, so no query can make the ordinary page show up
 *       through it and carry a control; {@code /api/pages?limit=1000} lists the same pages and is covered.</li>
 * </ul>
 */
public class WorkLeakIT {

    /** In the item and in the ordinary page: what the surfaces are queried by. */
    private static final String SHARED = "zqleakshared7";
    /** Only in the item (title and body). */
    private static final String ITEM_ONLY = "zqitemonly7";
    private static final String ITEM_TITLE = "Leak probe item";
    private static final String TARGET = "WorkLeakTarget";
    private static final String LINKER = "WorkLeakLinker";

    private static void put( final Caller admin, final String page, final String content ) throws Exception {
        final HttpResponse< String > r = admin.send( "PUT", "/api/pages/" + page,
            Map.of( "content", content, "changeNote", "WorkLeakIT" ) );
        assertTrue( r.statusCode() == 200 || r.statusCode() == 201, "save " + page + ": " + r.statusCode() + " " + r.body() );
    }

    /** Surfaces that index or list pages; each must show the ordinary page, via a 200, before absence means anything. */
    private static List< String > surfaces() {
        return List.of( "/api/search?q=" + SHARED, "/api/bundle?mode=lexical&q=" + SHARED,
            "/api/changes?since=2000-01-01T00:00:00Z", "/sitemap.xml", "/api/pages?limit=1000",
            "/api/recent-changes", "/api/structure/pages?limit=1000",
            "/api/backlinks/" + TARGET );
    }

    @Test
    void aWorkItemNeverAppearsOnAWikiSurface() throws Exception {
        final Caller admin = WorkIt.admin();
        put( admin, TARGET, "# Target\n\nA page that work items link to.\n" );

        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", "LEAK", "name", "Leak project" ) ).statusCode() );
        final HttpResponse< String > item = admin.send( "POST", "/api/work/items", Map.of( "project", "LEAK", "level", "epic",
            "title", ITEM_TITLE + " " + ITEM_ONLY, "body", SHARED + " " + ITEM_ONLY + " links [[" + TARGET + "]] and ["
                + TARGET + "](" + TARGET + ")." ) );
        assertEquals( 201, item.statusCode(), item.body() );
        final String itemKey = ( ( JsonObject ) WorkIt.json( item ) ).getAsJsonObject( "item" ).get( "key" ).getAsString();
        // the item exists and is readable where it should be
        assertEquals( 200, admin.get( "/api/work/items/" + itemKey ).statusCode() );

        // an ordinary page, created after the item, with the shared word and a link to the same target
        put( admin, LINKER, "# Linker\n\nThe word " + SHARED + " links to [" + TARGET + "](" + TARGET + ").\n" );

        for ( final String path : surfaces() ) {
            // positive control: a 200 that shows the ordinary page
            final String body = WorkIt.await( path, admin, path, b -> b.contains( LINKER ) );
            assertFalse( body.contains( ITEM_ONLY ), path + " leaked the item's own word: " + body );
            assertFalse( body.contains( itemKey ), path + " leaked the item key: " + body );
            assertFalse( body.contains( ITEM_TITLE ), path + " leaked the item title: " + body );
        }
        // the wikilink in the item body adds no backlink: the target's only referrer is the ordinary page
        final String backlinks = admin.get( "/api/backlinks/" + TARGET ).body();
        assertTrue( backlinks.contains( LINKER ), backlinks );
        assertFalse( backlinks.contains( "LEAK" ), backlinks );
    }
}
