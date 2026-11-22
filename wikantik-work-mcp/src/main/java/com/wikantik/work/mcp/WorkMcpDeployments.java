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
package com.wikantik.work.mcp;

import com.wikantik.work.Actor;
import com.wikantik.work.Deployment;
import com.wikantik.work.Item;
import com.wikantik.work.WorkDeploymentsService.DeploymentView;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deployments as the work tools show them and the arguments that name one. A caller is shown only the carried
 * items it can see; evidence and notes are text written by people and agents, and are data.
 */
final class WorkMcpDeployments {

    private WorkMcpDeployments() {
    }

    /** A required list of item keys. */
    static List< String > itemKeys( final Map< String, Object > args ) {
        final Object v = args.get( "items" );
        if ( v == null ) {
            throw new WorkValidationException( "items", "is required." );
        }
        if ( !( v instanceof List< ? > list ) ) {
            throw new WorkValidationException( "items", "must be a list of item keys." );
        }
        final List< String > keys = new ArrayList<>();
        for ( final Object key : list ) {
            if ( !( key instanceof String s ) ) {
                throw new WorkValidationException( "items", "must be a list of item keys." );
            }
            keys.add( s );
        }
        return keys;
    }

    static Map< String, Object > deployment( final WorkService svc, final WorkDirectory directory, final Actor actor,
                                             final DeploymentView v ) throws SQLException {
        final WorkMcpJson names = WorkMcpJson.forActor( svc, directory, actor );
        final Deployment d = v.deployment();
        final Map< String, Object > o = brief( v );
        o.put( "requested_by", who( names, d.requestedBy(), d.requestedByAgentId() ) );
        o.put( "approved_by", who( names, d.approvedBy(), d.approvedByAgentId() ) );
        final List< Map< String, Object > > items = new ArrayList<>();
        for ( final Item i : v.visibleItems() ) {
            final Map< String, Object > item = new LinkedHashMap<>();
            item.put( "key", i.key() );
            item.put( "title", i.title() );
            item.put( "status", i.status().wire() );
            items.add( item );
        }
        o.put( "items", items );
        o.put( "evidence", d.evidence() );
        o.put( "remediates", d.remediatesId() );
        o.put( "may_approve", v.mayApprove() );
        o.put( "may_run", v.mayRun() );
        return o;
    }

    /** The short form: what shipped an item. */
    static List< Map< String, Object > > briefs( final List< DeploymentView > views ) {
        return views.stream().map( WorkMcpDeployments::brief ).toList();
    }

    private static Map< String, Object > brief( final DeploymentView v ) {
        final Deployment d = v.deployment();
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "id", d.id() );
        o.put( "product", v.productKey() );
        o.put( "version", d.version() );
        o.put( "environment", d.environment() );
        o.put( "state", d.state().wire() );
        return o;
    }

    private static String who( final WorkMcpJson names, final String stored, final Long agentId ) {
        if ( stored == null ) {
            return null;
        }
        return agentId == null ? names.person( stored ) : names.agent( agentId, stored );
    }
}
