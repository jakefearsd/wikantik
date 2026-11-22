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
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/** Unclaimed ready or review items waiting in a queue the caller serves (an agent) or can see (a person). */
public final class ListClaimableTool extends WorkMcpTool {

    public static final String TOOL_NAME = "list_claimable";

    private static final ToolSpec SPEC = ToolSpec.reads( TOOL_NAME )
        .description( "Items you could claim now: unclaimed, ready or in review, in a queue you serve, in a "
            + "project you were admitted to as a member. Items in a project's active sprint come first, then by "
            + "priority and due date. Returns {items:[{key,title,level,kind,status,priority,"
            + "estimate,queue,queueKey,productKey,parentKey,dueOn}]}. An unknown or hidden project is \"Not found.\"" )
        .arg( "project", "string", "Only this project (its key).", "PLAT" )
        .arg( "queue", "string", "Only this queue (its key).", "implementer" )
        .arg( "limit", "integer", "Most items to return; default 20, at most 50.", 20 );

    private final WorkService svc;
    private final WorkDirectory directory;

    public ListClaimableTool( final WorkService svc, final WorkDirectory directory ) {
        super( SPEC );
        this.svc = svc;
        this.directory = directory;
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        final String project = stringArg( args, "project" );
        final String queue = stringArg( args, "queue" );
        final Integer limit = intArg( args, "limit" );
        final WorkMcpJson json = WorkMcpJson.forActor( svc, directory, actor );
        final List< Map< String, Object > > items = svc.claims()
            .claimable( actor, project, queue, limit == null ? 0 : limit ).stream().map( json::summary ).toList();
        return ok( Map.of( "items", items ) );
    }
}
