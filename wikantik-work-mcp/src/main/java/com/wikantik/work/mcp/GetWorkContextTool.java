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
import com.wikantik.work.Agent;
import com.wikantik.work.Item;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Who the caller is and what it can see: the agent (or the person whose work key it is), the projects it was
 * admitted to with its role in each, the items it holds a claim on, and how long a claim lasts.
 */
public final class GetWorkContextTool extends WorkMcpTool {

    public static final String TOOL_NAME = "get_work_context";

    private final WorkService svc;
    private final WorkDirectory directory;

    public GetWorkContextTool( final WorkService svc, final WorkDirectory directory ) {
        this.svc = svc;
        this.directory = directory;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Who you are and what you can see: your agent record (name, vendor, runtime, queues), the "
                + "projects you were admitted to with your role in each, the items you currently hold a claim on, "
                + "and claimExpiryMinutes (how long a claim lasts before it lapses). Call this first. Returns "
                + "{agent, person, projects:[{key,name,role}], claims:[{key,title,status,expiresAt}], "
                + "claimExpiryMinutes}." )
            .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
            .annotations( READ_ONLY )
            .build();
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        final Map< String, Object > out = new LinkedHashMap<>();
        final WorkMcpJson json = WorkMcpJson.forActor( svc, directory, actor );
        final boolean agent = actor.kind() == Actor.Kind.AGENT;
        out.put( "agent", agent ? agent( actor, json ) : null );
        out.put( "person", agent ? null : json.person( actor.login() ) );
        final List< Map< String, Object > > projects = new ArrayList<>();
        for ( final WorkService.ProjectRole pr : svc.listProjectsWithRoles( actor ) ) {
            final Map< String, Object > p = new LinkedHashMap<>();
            p.put( "key", pr.project().key() );
            p.put( "name", pr.project().name() );
            p.put( "role", pr.role().wire() );
            projects.add( p );
        }
        out.put( "projects", projects );
        final List< Map< String, Object > > claims = new ArrayList<>();
        for ( final Item held : svc.claims().myClaims( actor ) ) {
            final Map< String, Object > c = new LinkedHashMap<>();
            c.put( "key", held.key() );
            c.put( "title", held.title() );
            c.put( "status", held.status().wire() );
            c.put( "expiresAt", held.claimExpiresAt() == null ? null : held.claimExpiresAt().toString() );
            claims.add( c );
        }
        out.put( "claims", claims );
        out.put( "claimExpiryMinutes", svc.claims().claimExpiry().toMinutes() );
        return ok( out );
    }

    private Map< String, Object > agent( final Actor actor, final WorkMcpJson json ) throws SQLException {
        final Map< String, Object > a = new LinkedHashMap<>();
        final Agent self = svc.registry().self( actor ).orElse( null );
        a.put( "name", self != null ? self.name() : actor.login() );
        a.put( "vendor", self == null ? null : self.vendor() );
        a.put( "runtime", self == null ? null : self.runtime() );
        final List< Map< String, Object > > queues = new ArrayList<>();
        if ( self != null ) {
            for ( final String key : self.queues() ) {
                final Map< String, Object > q = new LinkedHashMap<>();
                q.put( "key", key );
                q.put( "name", json.queue( key ) );
                queues.add( q );
            }
        }
        a.put( "queues", queues );
        return a;
    }
}
