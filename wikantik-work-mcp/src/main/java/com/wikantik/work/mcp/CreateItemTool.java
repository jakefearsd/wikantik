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

import com.wikantik.mcp.tools.McpAudit;
import com.wikantik.work.Actor;
import com.wikantik.work.ItemDraft;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkService.ItemView;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates an item. It always lands as a {@code draft}, unassigned, for a person to refine: the owner approved agents
 * creating items on that condition, so {@code status} and {@code assignee} are refused by name.
 */
public final class CreateItemTool extends WorkMcpTool {

    public static final String TOOL_NAME = "create_item";

    private static final Set< String > ALLOWED = Set.of( "project", "level", "title", "body", "parentKey",
        "productKey", "kind", "priority", "queue" );
    private static final Map< String, String > REASONS = Map.of(
        "status", "New items always start as drafts.",
        "assignee", UpdateItemTool.USE_HANDOFF,
        "autonomy", UpdateItemTool.NOT_SETTABLE, "doneMeans", UpdateItemTool.NOT_SETTABLE,
        "allowDirect", UpdateItemTool.NOT_SETTABLE );

    private final WorkService svc;

    public CreateItemTool( final WorkService svc ) {
        this.svc = svc;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "project", prop( "string", "The project key.", "PLAT" ) );
        props.put( "level", prop( "string", "epic, story or task.", "task" ) );
        props.put( "title", prop( "string", "The title.", "Retry failed uploads" ) );
        props.put( "body", prop( "string", "The body, Markdown source.", "Why and what." ) );
        props.put( "parentKey", prop( "string", "The parent item's key.", "PLAT-2" ) );
        props.put( "productKey", prop( "string", "The product's key.", "web" ) );
        props.put( "kind", prop( "string", "software (default), docs or admin.", "software" ) );
        props.put( "priority", prop( "integer", "1 (highest) to 4.", 3 ) );
        props.put( "queue", prop( "string", "The queue key to put it in.", "implementer" ) );
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Create a work item. It is always created as a draft with no assignee, for a person to "
                + "refine; status and assignee are refused by name. Returns {key,version,status}. Use handoff_item "
                + "afterwards to give it to someone." )
            .inputSchema( schema( props, List.of( "project", "level", "title" ) ) )
            .annotations( WRITES )
            .build();
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, ALLOWED, REASONS );
        final String project = requiredString( args, "project" );
        final String level = requiredString( args, "level" );
        final String title = requiredString( args, "title" );
        final String body = stringArg( args, "body" );
        final String parentKey = stringArg( args, "parentKey" );
        final String productKey = stringArg( args, "productKey" );
        final String kind = stringArg( args, "kind" );
        final Integer priority = intArg( args, "priority" );
        final String queue = stringArg( args, "queue" );
        final ItemDraft draft = new ItemDraft( project, WorkLevel.fromWire( level ), parentKey, productKey,
            kind == null || kind.isBlank() ? WorkKind.SOFTWARE : WorkKind.fromWire( kind ), title, body, priority, null,
            null, queue );
        final ItemView view = svc.createItem( actor, draft );
        McpAudit.logWrite( TOOL_NAME, "created", view.item().key(), actor.login() );
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "key", view.item().key() );
        out.put( "version", view.item().version() );
        out.put( "status", view.item().status().wire() );
        return ok( out );
    }
}
