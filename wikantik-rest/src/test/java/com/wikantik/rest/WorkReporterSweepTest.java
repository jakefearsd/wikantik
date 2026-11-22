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

import com.google.gson.JsonParser;
import com.wikantik.work.Actor;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A person who holds only the {@code reporter} role in a project is, to every work route that existed before intake,
 * a stranger: the service refuses them (not found from every member method) and REST maps that to the one 404 body.
 * This pins the mapping for every route family with the service mocked to behave that way; the end-to-end proof, with
 * the real service and database, is the integration test. It also proves no existing resource asks the intake
 * service anything on a refused call (there is no reporter branch in any of them).
 */
class WorkReporterSweepTest {

    private static final Actor RITA = Actor.human( "rita", Set.of( "Customers" ), false );
    private static final Actor MALLORY = Actor.human( "mallory", Set.of(), false );
    private static final String NOT_FOUND = "{\"error\":true,\"status\":404,\"message\":\"Not found.\"}";

    /** One request to an existing route family. */
    private record Req( Supplier< WorkResourceBase > servlet, String servletPath, String method, String path,
                        Map< String, String > query, String body ) {
    }

    private static Req req( final Supplier< WorkResourceBase > servlet, final String servletPath, final String method,
                            final String path, final String body ) {
        return new Req( servlet, servletPath, method, path, Map.of(), body );
    }

    private static Req qreq( final Supplier< WorkResourceBase > servlet, final String servletPath, final String method,
                             final String path, final Map< String, String > query ) {
        return new Req( servlet, servletPath, method, path, query, null );
    }

    private static final String P = "/api/work/projects";
    private static final String I = "/api/work/items";
    private static final String PR = "/api/work/products";

    private static List< Req > routes() {
        final Supplier< WorkResourceBase > projects = WorkProjectResource::new;
        final Supplier< WorkResourceBase > items = WorkItemResource::new;
        final Supplier< WorkResourceBase > products = WorkProductResource::new;
        final Supplier< WorkResourceBase > deployments = WorkDeploymentResource::new;
        final Supplier< WorkResourceBase > sprints = WorkSprintResource::new;
        final Supplier< WorkResourceBase > changes = WorkChangeResource::new;
        final Supplier< WorkResourceBase > inbox = WorkInboxResource::new;
        final Supplier< WorkResourceBase > filters = WorkFilterResource::new;
        final Supplier< WorkResourceBase > activity = WorkActivityResource::new;
        final Supplier< WorkResourceBase > agents = WorkAgentResource::new;
        final Supplier< WorkResourceBase > queues = WorkQueueResource::new;
        final String ver = "{\"expectedVersion\":1,\"changes\":{\"title\":\"x\"}}";
        final List< Req > r = new ArrayList<>();
        // projects, members, board, labels, sprints, flow, webhooks
        r.add( req( projects, P, "GET", "/PLAT", "" ) );
        r.add( req( projects, P, "PATCH", "/PLAT", "{\"name\":\"x\"}" ) );
        r.add( req( projects, P, "POST", "/PLAT/archive", (String) null ) );
        r.add( req( projects, P, "GET", "/PLAT/members", (String) null ) );
        r.add( req( projects, P, "PUT", "/PLAT/members", "{\"principal\":\"rita\",\"principalKind\":\"user\",\"role\":\"lead\"}" ) );
        r.add( qreq( projects, P, "DELETE", "/PLAT/members", Map.of( "principal", "rita", "principalKind", "user" ) ) );
        r.add( qreq( projects, P, "GET", "/PLAT/board", Map.of() ) );
        r.add( qreq( projects, P, "GET", "/PLAT/labels", Map.of() ) );
        r.add( qreq( projects, P, "GET", "/PLAT/sprints", Map.of() ) );
        r.add( req( projects, P, "POST", "/PLAT/sprints", "{\"name\":\"S1\"}" ) );
        r.add( qreq( projects, P, "GET", "/PLAT/velocity", Map.of() ) );
        r.add( qreq( projects, P, "GET", "/PLAT/change-counts", Map.of() ) );
        r.add( qreq( projects, P, "GET", "/PLAT/flow", Map.of() ) );
        r.add( qreq( projects, P, "GET", "/PLAT/dora", Map.of() ) );
        r.add( qreq( projects, P, "GET", "/PLAT/webhooks", Map.of() ) );
        r.add( req( projects, P, "POST", "/PLAT/webhooks", "{\"url\":\"https://example.org/h\"}" ) );
        // items, comments, links, changes, attachments, find
        r.add( qreq( items, I, "GET", "/PLAT-1", Map.of() ) );
        r.add( req( items, I, "PATCH", "/PLAT-1", ver ) );
        r.add( qreq( items, I, "GET", "/PLAT-1/revisions", Map.of() ) );
        r.add( qreq( items, I, "GET", "/PLAT-1/events", Map.of() ) );
        r.add( req( items, I, "POST", "/PLAT-1/comments", "{\"body\":\"hi\"}" ) );
        r.add( qreq( items, I, "GET", "/PLAT-1/changes", Map.of() ) );
        r.add( req( items, I, "POST", "/PLAT-1/changes", "{\"branch\":\"b\"}" ) );
        r.add( qreq( items, I, "GET", "/PLAT-1/policy", Map.of() ) );
        r.add( qreq( items, I, "GET", "/PLAT-1/shipped-in", Map.of() ) );
        r.add( qreq( items, I, "GET", "/PLAT-1/points", Map.of() ) );
        r.add( req( items, I, "POST", "/PLAT-1/acknowledge", (String) null ) );
        r.add( req( items, I, "POST", "/PLAT-1/claim", (String) null ) );
        r.add( req( items, I, "POST", "/PLAT-1/release", (String) null ) );
        r.add( req( items, I, "POST", "/PLAT-1/handoff", "{\"expectedVersion\":1,\"status\":\"done\"}" ) );
        r.add( req( items, I, "POST", "/PLAT-1/links", "{\"kind\":\"blocks\",\"target\":\"PLAT-2\"}" ) );
        r.add( qreq( items, I, "GET", "/PLAT-1/attachments", Map.of() ) );
        r.add( qreq( items, I, "GET", "/PLAT-1/attachments/3", Map.of() ) );
        r.add( req( items, I, "DELETE", "/PLAT-1/attachments/3", (String) null ) );
        r.add( req( items, I, "POST", "", "{\"project\":\"PLAT\",\"level\":\"task\",\"title\":\"T\"}" ) );
        r.add( qreq( items, I, "GET", "", Map.of( "project", "PLAT" ) ) );
        r.add( qreq( items, I, "GET", "", Map.of( "q", "anything" ) ) );
        r.add( req( items, "/api/work/comments", "PATCH", "/5", "{\"body\":\"x\"}" ) );
        r.add( req( items, "/api/work/comments", "DELETE", "/5", (String) null ) );
        r.add( req( items, "/api/work/links", "DELETE", "/5", (String) null ) );
        // changes, deployments, sprints
        r.add( req( changes, "/api/work/changes", "PATCH", "/1", "{\"headSha\":\"abc\"}" ) );
        r.add( req( changes, "/api/work/changes", "POST", "/1/request-review", (String) null ) );
        r.add( req( changes, "/api/work/changes", "POST", "/1/review", "{\"approved\":true}" ) );
        r.add( req( changes, "/api/work/changes", "POST", "/1/merge", "{\"mergedSha\":\"abc\"}" ) );
        r.add( req( changes, "/api/work/changes", "POST", "/1/acknowledge-flag", (String) null ) );
        r.add( req( changes, "/api/work/changes", "POST", "/1/abandon", (String) null ) );
        r.add( qreq( deployments, "/api/work/deployments", "GET", "/4", Map.of() ) );
        r.add( qreq( deployments, "/api/work/deployments", "GET", "/4/history", Map.of() ) );
        r.add( qreq( deployments, "/api/work/deployments", "GET", "/4/gate", Map.of( "version", "1" ) ) );
        r.add( req( deployments, "/api/work/deployments", "POST", "/4/approve", "{}" ) );
        r.add( req( deployments, "/api/work/deployments", "POST", "/4/start", (String) null ) );
        r.add( req( deployments, "/api/work/deployments", "POST", "/4/verify", "{\"evidence\":\"ok\"}" ) );
        r.add( req( deployments, "/api/work/deployments", "POST", "",
            "{\"product\":\"web\",\"environment\":\"production\",\"version\":\"1\",\"items\":[\"PLAT-1\"]}" ) );
        r.add( qreq( sprints, "/api/work/sprints", "GET", "/3", Map.of() ) );
        r.add( qreq( sprints, "/api/work/sprints", "GET", "/3/report", Map.of() ) );
        r.add( req( sprints, "/api/work/sprints", "POST", "/3/start", "{\"version\":1}" ) );
        // saved filters, inbox (foreign items), watch, activity, products, flow, agents, queues
        r.add( req( filters, "/api/work/filters", "PATCH", "/3", "{\"name\":\"x\"}" ) );
        r.add( req( filters, "/api/work/filters", "DELETE", "/3", (String) null ) );
        r.add( req( inbox, "/api/work/inbox", "POST", "/read/5", (String) null ) );
        r.add( qreq( inbox, "/api/work/watch", "GET", "/PLAT-1", Map.of() ) );
        r.add( req( inbox, "/api/work/watch", "PUT", "/PLAT-1", "{\"state\":\"watching\"}" ) );
        r.add( qreq( activity, "/api/work/activity", "GET", "", Map.of( "project", "PLAT" ) ) );
        r.add( req( products, PR, "PATCH", "/web", "{\"name\":\"x\"}" ) );
        r.add( qreq( products, PR, "GET", "/web/history", Map.of() ) );
        r.add( req( products, PR, "POST", "/web/archive", (String) null ) );
        r.add( qreq( products, PR, "GET", "/web/dora", Map.of() ) );
        r.add( req( products, PR, "POST", "/web/hook", "{}" ) );
        r.add( req( products, PR, "DELETE", "/web/hook", (String) null ) );
        r.add( req( agents, "/api/work/agents", "PATCH", "/5", "{\"name\":\"x\"}" ) );
        r.add( req( agents, "/api/work/agents", "POST", "/5/rotate-key", (String) null ) );
        r.add( req( agents, "/api/work/agents", "POST", "/5/retire", (String) null ) );
        r.add( req( agents, "/api/work/agents", "PUT", "/5/sponsor", "{\"sponsor\":\"rita\"}" ) );
        r.add( req( queues, "/api/work/queues", "PATCH", "/triage", "{\"name\":\"x\"}" ) );
        r.add( req( queues, "/api/work/queues", "POST", "/triage/retire", (String) null ) );
        // the rest of the registered routes (the completeness guard below names each route table's coverage)
        r.add( qreq( projects, P, "GET", "", Map.of() ) );
        r.add( req( projects, P, "POST", "", "{\"key\":\"NEW\",\"name\":\"New\"}" ) );
        r.add( req( projects, P, "PATCH", "/PLAT/webhooks/4", "{\"enabled\":false}" ) );
        r.add( req( projects, P, "DELETE", "/PLAT/webhooks/4", (String) null ) );
        r.add( qreq( items, "/api/work", "GET", "/claimable", Map.of() ) );
        r.add( qreq( products, PR, "GET", "", Map.of() ) );
        r.add( req( products, PR, "POST", "", "{\"key\":\"web\",\"name\":\"Web\"}" ) );
        r.add( qreq( deployments, "/api/work/deployments", "GET", "", Map.of( "product", "web", "environment", "production" ) ) );
        r.add( req( deployments, "/api/work/deployments", "POST", "/4/reject", (String) null ) );
        r.add( req( deployments, "/api/work/deployments", "POST", "/4/fail", (String) null ) );
        r.add( req( deployments, "/api/work/deployments", "POST", "/4/roll-back", (String) null ) );
        r.add( req( sprints, "/api/work/sprints", "PATCH", "/3", "{\"version\":1,\"name\":\"x\"}" ) );
        r.add( req( sprints, "/api/work/sprints", "POST", "/3/close", "{\"version\":1}" ) );
        r.add( qreq( filters, "/api/work/filters", "GET", "", Map.of() ) );
        r.add( req( filters, "/api/work/filters", "POST", "", "{\"name\":\"f\"}" ) );
        r.add( qreq( activity, "/api/work/log", "GET", "", Map.of() ) );
        r.add( qreq( agents, "/api/work/agents", "GET", "", Map.of() ) );
        r.add( req( agents, "/api/work/agents", "POST", "", "{\"name\":\"bot\"}" ) );
        r.add( req( agents, "/api/work/agents", "DELETE", "/5/sponsor", (String) null ) );
        r.add( qreq( queues, "/api/work/queues", "GET", "", Map.of() ) );
        r.add( req( queues, "/api/work/queues", "POST", "", "{\"key\":\"triage\"}" ) );
        return r;
    }

    /**
     * A work service that behaves as the real one does for someone with no standing anywhere: every method is not
     * found, and the sub-services it hands out do the same. Every call to {@code intake()} is recorded.
     */
    private static WorkService refusing( final List< String > intakeCalls ) {
        final Answer< Object > notFound = new Answer<>() {
            @Override
            public Object answer( final InvocationOnMock inv ) {
                final Class< ? > type = inv.getMethod().getReturnType();
                if ( "intake".equals( inv.getMethod().getName() ) ) {
                    intakeCalls.add( inv.getMethod().toString() );
                }
                if ( type.getName().startsWith( "com.wikantik.work." )
                        && ( type.getSimpleName().endsWith( "Service" ) || type == WorkFeatures.class ) ) {
                    return Mockito.mock( type, this );
                }
                throw new WorkNotFoundException( "no standing: " + inv.getMethod().getName() );
            }
        };
        return Mockito.mock( WorkService.class, notFound );
    }

    private static WorkHttp.Result run( final Req q, final Actor who, final List< String > intakeCalls ) throws Exception {
        final WorkResourceBase servlet = WorkHttp.wire( q.servlet().get(), refusing( intakeCalls ), who );
        return WorkHttp.call( servlet, q.method(), q.servletPath(), q.path().isEmpty() ? null : q.path(), q.query(), q.body() );
    }

    @Test
    void everyExistingRouteAnswersAReporterAsItAnswersAStranger() throws Exception {
        final List< String > intakeCalls = new ArrayList<>();
        final List< String > wrong = new ArrayList<>();
        final List< Req > all = routes();
        for ( final Req q : all ) {
            final WorkHttp.Result reporter = run( q, RITA, intakeCalls );
            final WorkHttp.Result stranger = run( q, MALLORY, intakeCalls );
            final boolean ok = reporter.status() == 404 && stranger.status() == 404
                && JsonParser.parseString( NOT_FOUND ).equals( reporter.json() )
                && reporter.body().equals( stranger.body() );
            if ( !ok ) {
                wrong.add( q.method() + " " + q.servletPath() + q.path() + " -> reporter " + reporter.status() + " "
                    + reporter.body().replace( '\n', ' ' ) + " | stranger " + stranger.status() );
            }
        }
        assertTrue( wrong.isEmpty(), "routes that did not answer the one 404 body:\n" + String.join( "\n", wrong ) );
        assertTrue( all.size() >= 70, "the sweep covers every family: " + all.size() );
        assertEquals( List.of(), intakeCalls, "no existing route asks the intake service anything for a refused caller" );
    }

    @Test
    void aReporterAndAStrangerGetTheSameBodyForAMalformedKeyToo() throws Exception {
        final Req bad = qreq( WorkItemResource::new, I, "GET", "/not%20a%20key", Map.of() );
        assertEquals( run( bad, RITA, new ArrayList<>() ).body(), run( bad, MALLORY, new ArrayList<>() ).body() );
    }

    // ---- completeness guard

    /**
     * Routes a reporter may legitimately use, so that they are not in the 404 sweep. Each answers a reporter for their
     * OWN data and is covered by its own test (the service decides, there is no reporter branch in REST).
     */
    private static final Map< String, String > REPORTER_ROUTES = Map.ofEntries(
        // the intake routes: the reporter's own surface
        Map.entry( "GET intake/projects", "WorkIntakeResourceTest" ),
        Map.entry( "GET intake/items", "WorkIntakeResourceTest" ),
        Map.entry( "POST intake/items", "WorkIntakeResourceTest.submitReturns201WithTheReportedView" ),
        Map.entry( "GET intake/items/{}", "WorkIntakeResourceTest" ),
        Map.entry( "POST intake/items/{}/comments", "WorkIntakeResourceTest" ),
        // /me: the caller's own identity and reportable projects
        Map.entry( "GET me", "WorkMeResourceTest" ),
        // the inbox, unread count, mark-read and notification preferences: the caller's own notices
        Map.entry( "GET inbox", "WorkInboxResourceTest.aReportersNoticeCarriesNoNoteNoTargetAndNoLogin" ),
        Map.entry( "GET inbox/unread", "WorkInboxResourceTest.unreadCount" ),
        Map.entry( "POST inbox/read-all", "WorkInboxResourceTest.markAllReadIs204" ),
        Map.entry( "GET notify-prefs", "WorkInboxResourceTest.preferencesAreRead" ),
        Map.entry( "PUT notify-prefs", "WorkInboxResourceTest.preferencesAreSet" ),
        // attach to the reporter's own item: WorkAttachmentRoutesTest (and the integration test)
        Map.entry( "POST items/{}/attachments", "WorkAttachmentRoutesTest" ) );

    private static List< String > registeredRoutes() throws Exception {
        final java.nio.file.Path dir = java.nio.file.Path.of(
            WorkResourceBase.class.getProtectionDomain().getCodeSource().getLocation().toURI() )
            .resolve( "com/wikantik/rest" );
        final List< String > out = new ArrayList<>();
        try ( java.util.stream.Stream< java.nio.file.Path > files = java.nio.file.Files.list( dir ) ) {
            for ( final java.nio.file.Path f : files.sorted().toList() ) {
                final String name = f.getFileName().toString();
                if ( !name.matches( "Work\\w+\\.class" ) ) {
                    continue;
                }
                final Class< ? > type = Class.forName( "com.wikantik.rest." + name.replace( ".class", "" ) );
                if ( WorkResourceBase.class.isAssignableFrom( type ) && type != WorkResourceBase.class
                        && !java.lang.reflect.Modifier.isAbstract( type.getModifiers() ) ) {
                    final WorkResourceBase servlet = ( WorkResourceBase ) type.getDeclaredConstructor().newInstance();
                    servlet.registeredRoutes().forEach( rt -> out.add( rt ) );
                }
            }
        }
        return out;
    }

    /** A request as {@code METHOD segments}, under {@code /api/work/}. */
    private static boolean covers( final Req q, final String route ) {
        final String[] mp = route.split( " ", 2 );
        if ( !q.method().equals( mp[ 0 ] ) ) {
            return false;
        }
        final String[] pattern = mp[ 1 ].isEmpty() ? new String[ 0 ] : mp[ 1 ].split( "/" );
        final List< String > segs = new ArrayList<>();
        for ( final String part : ( q.servletPath().substring( "/api/work".length() ) + "/" + q.path() ).split( "/" ) ) {
            if ( !part.isEmpty() ) {
                segs.add( part );
            }
        }
        if ( pattern.length != segs.size() ) {
            return false;
        }
        for ( int i = 0; i < pattern.length; i++ ) {
            if ( !"{}".equals( pattern[ i ] ) && !pattern[ i ].equals( segs.get( i ) ) ) {
                return false;
            }
        }
        return true;
    }

    /**
     * Nothing forces a new route into the sweep by hand, so this does: every route a Work resource registers (read
     * from its own route table, the one the servlet dispatches by) is either hit by a sweep request or named, with its
     * covering test, in {@link #REPORTER_ROUTES}. A new route fails here until someone decides which it is. (Not
     * covered: {@code /api/work/hooks/*}, {@code WorkHookResource}, which is signature-authenticated and takes no actor.)
     */
    @Test
    void everyRegisteredRouteIsInTheSweepOrNamedAsAReporterRoute() throws Exception {
        final List< Req > sweep = routes();
        final List< String > registered = registeredRoutes();
        assertTrue( registered.size() > 100, "the route tables were read: " + registered.size() );
        final List< String > missing = new ArrayList<>();
        for ( final String route : registered ) {
            if ( sweep.stream().noneMatch( q -> covers( q, route ) ) && !REPORTER_ROUTES.containsKey( route ) ) {
                missing.add( route );
            }
        }
        assertTrue( missing.isEmpty(), "routes in neither the sweep nor REPORTER_ROUTES:\n" + String.join( "\n", missing ) );
        final List< String > stale = new ArrayList<>( REPORTER_ROUTES.keySet() );
        stale.removeAll( registered );
        assertTrue( stale.isEmpty(), "REPORTER_ROUTES names routes that no longer exist: " + stale );
    }
}
