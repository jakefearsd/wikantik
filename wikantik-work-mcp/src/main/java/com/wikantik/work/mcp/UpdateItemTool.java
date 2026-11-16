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
import com.wikantik.work.ItemPatch;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkService.ItemView;
import com.wikantik.work.WorkValidationException;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Changes an item's title, body, status, priority or due date. It deliberately offers less than the service: who has
 * the item changes only through {@code handoff_item}, and policy and structure are not an agent's to set. Everything
 * else is refused by name, before the service is called, so an agent never believes it changed something it did not.
 */
public final class UpdateItemTool extends WorkMcpTool {

    public static final String TOOL_NAME = "update_item";

    static final String USE_HANDOFF = "Use handoff_item to change who has the item.";
    static final String NOT_SETTABLE = "That field is not settable through this endpoint.";

    private static final Set< String > SETTABLE = Set.of( "title", "body", "status", "priority", "dueOn" );
    private static final Set< String > ALLOWED = Set.of( "key", "expectedVersion", "title", "body", "status",
        "priority", "dueOn", "note" );
    private static final Map< String, String > REASONS = Map.ofEntries(
        Map.entry( "assignee", USE_HANDOFF ), Map.entry( "queue", USE_HANDOFF ),
        Map.entry( "autonomy", NOT_SETTABLE ), Map.entry( "doneMeans", NOT_SETTABLE ),
        Map.entry( "allowDirect", NOT_SETTABLE ), Map.entry( "parentKey", NOT_SETTABLE ),
        Map.entry( "productKey", NOT_SETTABLE ), Map.entry( "project", NOT_SETTABLE ),
        Map.entry( "level", NOT_SETTABLE ), Map.entry( "kind", NOT_SETTABLE ), Map.entry( "rank", NOT_SETTABLE ) );

    private final WorkService svc;
    private final WorkDirectory directory;

    public UpdateItemTool( final WorkService svc, final WorkDirectory directory ) {
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
        props.put( "key", prop( "string", "The item key.", "PLAT-12" ) );
        props.put( "expectedVersion", prop( "integer", "The item's version as you last read it; a stale one is "
            + "refused with the current version.", 3 ) );
        props.put( "title", prop( "string", "New title.", "Retry failed uploads" ) );
        props.put( "body", prop( "string", "New body, Markdown source.", "Why and what." ) );
        props.put( "status", prop( "string", "New status: draft, refining, ready, in_progress, review, done or "
            + "cancelled. To give the item to someone else use handoff_item.", "in_progress" ) );
        props.put( "priority", prop( "integer", "1 (highest) to 4.", 2 ) );
        props.put( "dueOn", prop( "string", "ISO date, or null to clear.", "2026-12-01" ) );
        props.put( "note", prop( "string", "Why you changed it; recorded in the item's history.", "Started work." ) );
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Change an item's title, body, status, priority or dueOn, as the agent you are. Give at "
                + "least one of them. It cannot change who has the item (use handoff_item) or any policy or "
                + "structural field; those arguments are refused by name. Returns the item as get_item does. "
                + "Refusals start stale: (re-read and retry), denied:, archived: or name the field." )
            .inputSchema( schema( props, List.of( "key", "expectedVersion" ) ) )
            .annotations( WRITES )
            .build();
    }

    @Override
    protected Integer currentVersion( final Actor actor, final Map< String, Object > args ) {
        return versionOf( svc, actor, args );
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, ALLOWED, REASONS );
        final String key = itemKey( args );
        final int expectedVersion = requiredInt( args, "expectedVersion" );
        final Map< String, Object > changes = new LinkedHashMap<>();
        for ( final String field : SETTABLE ) {
            if ( args.containsKey( field ) ) {
                changes.put( field, "priority".equals( field ) ? priority( args ) : stringArg( args, field ) );
            }
        }
        final String note = stringArg( args, "note" );
        if ( changes.isEmpty() ) {
            throw new WorkValidationException( "changes", "Nothing to change." );
        }
        final ItemView view = svc.updateItem( actor, key, new ItemPatch( expectedVersion, changes, note ) );
        McpAudit.logWrite( TOOL_NAME, "updated", key, actor.login() );
        return ok( WorkMcpJson.fullItem( svc, directory, actor, view ) );
    }

    private static Object priority( final Map< String, Object > args ) {
        return intArg( args, "priority" );
    }
}
