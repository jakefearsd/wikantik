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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unclaimed ready or review items waiting in a queue the caller serves (an agent) or can see (a person). */
public final class ListClaimableTool extends WorkMcpTool {

    public static final String TOOL_NAME = "list_claimable";

    private final WorkService svc;
    private final WorkDirectory directory;

    public ListClaimableTool( final WorkService svc, final WorkDirectory directory ) {
        this.svc = svc;
        this.directory = directory;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "project", Map.of( "type", "string", "description", "Only this project (its key).",
            "examples", List.of( "PLAT" ) ) );
        props.put( "queue", Map.of( "type", "string", "description", "Only this queue (its key).",
            "examples", List.of( "implementer" ) ) );
        props.put( "limit", Map.of( "type", "integer", "description", "Most items to return; default 20, at most 50.",
            "examples", List.of( 20 ) ) );
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Items you could claim now: unclaimed, ready or in review, in a queue you serve, in a "
                + "project you were admitted to as a member. Returns {items:[{key,title,level,kind,status,priority,"
                + "queue,queueKey,productKey,parentKey,dueOn}]}. An unknown or hidden project is \"Not found.\"" )
            .inputSchema( new McpSchema.JsonSchema( "object", props, List.of(), null, null, null ) )
            .annotations( READ_ONLY )
            .build();
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
