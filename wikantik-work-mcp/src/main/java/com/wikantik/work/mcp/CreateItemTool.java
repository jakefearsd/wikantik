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
import com.wikantik.work.ItemDraft;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkType;
import com.wikantik.work.WorkService.ItemView;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/**
 * Creates an item. It always lands as a {@code draft}, unassigned, for a person to refine: the owner approved agents
 * creating items on that condition, so {@code status} and {@code assignee} are refused by name.
 */
public final class CreateItemTool extends WorkMcpTool {

    public static final String TOOL_NAME = "create_item";

    private static final ToolSpec SPEC = ToolSpec.writes( TOOL_NAME )
        .description( "Create a work item. It is always created as a draft with no assignee, for a person to "
            + "refine; status and assignee are refused by name. Optionally a type and labels. Returns {key,version,status}. Use handoff_item "
            + "afterwards to give it to someone." )
        .requiredArg( "project", "string", "The project key.", "PLAT" )
        .requiredArg( "level", "string", "epic, story or task.", "task" )
        .requiredArg( "title", "string", "The title.", "Retry failed uploads" )
        .arg( "body", "string", "The body, Markdown source.", "Why and what." )
        .arg( "parentKey", "string", "The parent item's key.", "PLAT-2" )
        .arg( "productKey", "string", "The product's key. Omit it to use the project's default product; pass an empty string for none.", "web" )
        .arg( "kind", "string", "software (default), docs or admin.", "software" )
        .arg( "type", "string", "feature (default), bug or chore; an epic is always a feature.", "bug" )
        .arg( "labels", labelsSchema(), false )
        .arg( "priority", "integer", "1 (highest) to 4.", 3 )
        .arg( "queue", "string", "The queue key to put it in.", "implementer" );

    private static final Map< String, String > REASONS = Map.of(
        "status", "New items always start as drafts.",
        "assignee", UpdateItemTool.USE_HANDOFF,
        "autonomy", UpdateItemTool.NOT_SETTABLE, "doneMeans", UpdateItemTool.NOT_SETTABLE,
        "allowDirect", UpdateItemTool.NOT_SETTABLE );

    private final WorkService svc;

    public CreateItemTool( final WorkService svc ) {
        super( SPEC );
        this.svc = svc;
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, allowed(), REASONS );
        final String project = requiredString( args, "project" );
        final String level = requiredString( args, "level" );
        final String title = requiredString( args, "title" );
        final String body = stringArg( args, "body" );
        final String parentKey = stringArg( args, "parentKey" );
        final String productKey = stringArg( args, "productKey" );
        final String kind = stringArg( args, "kind" );
        final Integer priority = intArg( args, "priority" );
        final String queue = stringArg( args, "queue" );
        final String type = stringArg( args, "type" );
        final List< String > labels = stringListArg( args, "labels" );
        final ItemDraft draft = new ItemDraft( project, WorkLevel.fromWire( level ), parentKey, productKey,
            kind == null || kind.isBlank() ? WorkKind.SOFTWARE : WorkKind.fromWire( kind ), title, body, priority, null,
            null, queue,
            type == null || type.isBlank() ? null : WorkType.fromWire( type ), labels );
        final ItemView view = svc.createItem( actor, draft );
        WorkMcpLog.written( TOOL_NAME, "created", view.item().key(), actor );
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "key", view.item().key() );
        out.put( "version", view.item().version() );
        out.put( "status", view.item().status().wire() );
        return ok( out );
    }
}
