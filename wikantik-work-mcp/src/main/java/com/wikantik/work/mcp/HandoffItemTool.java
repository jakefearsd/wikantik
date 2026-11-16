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
import com.wikantik.work.WorkClaimsService.Handoff;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkService.ItemView;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkValidationException;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/**
 * Hands an item on to a queue or to a person, with a status and a note, in one recorded step; any claim on it ends.
 * The note is required here even where the service might allow none: every hand-off is an explicit state change the
 * owner wants to see explained.
 */
public final class HandoffItemTool extends WorkMcpTool {

    public static final String TOOL_NAME = "handoff_item";

    private static final Set< String > ALLOWED = Set.of( "key", "expectedVersion", "status", "assignee", "queue", "note" );

    private final WorkService svc;
    private final WorkDirectory directory;

    public HandoffItemTool( final WorkService svc, final WorkDirectory directory ) {
        this.svc = svc;
        this.directory = directory;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    protected boolean writes() {
        return true;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "key", prop( "string", "The item key.", "PLAT-12" ) );
        props.put( "expectedVersion", prop( "integer", "The item's version as you last read it.", 3 ) );
        props.put( "status", prop( "string", "The status the item takes: draft, refining, ready, in_progress, "
            + "review, done or cancelled.", "review" ) );
        props.put( "assignee", prop( "string", "A person's DISPLAY NAME, never a login. Give this or queue.",
            "Lena Lead" ) );
        props.put( "queue", prop( "string", "A queue key. Give this or assignee.", "reviewer" ) );
        props.put( "note", prop( "string", "What you did and what is expected next; required.",
            "Implemented with tests; please review the retry logic." ) );
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Hand an item on, as the agent you are, to a queue or a person, with a status and a note "
                + "saying what was done and what is expected next. Any claim you hold on it ends. Give exactly one "
                + "of assignee (display name) or queue. Returns the item as get_item does. Refusals: stale: "
                + "(re-read and retry), denied:, archived:, or the field name." )
            .inputSchema( schema( props, List.of( "key", "expectedVersion", "status", "note" ) ) )
            .annotations( WRITES )
            .build();
    }

    @Override
    protected Integer currentVersion( final Actor actor, final Map< String, Object > args ) {
        return versionOf( svc, actor, args );
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, ALLOWED, Map.of() );
        final String key = itemKey( args );
        final int expectedVersion = requiredInt( args, "expectedVersion" );
        final String status = requiredString( args, "status" );
        final String assignee = stringArg( args, "assignee" );
        final String queue = stringArg( args, "queue" );
        final boolean person = assignee != null && !assignee.isBlank();
        final boolean toQueue = queue != null && !queue.isBlank();
        if ( person == toQueue ) {
            throw new WorkValidationException( "assignee",
                "Name a person (assignee) or a queue, not both and not neither." );
        }
        final String note = requiredString( args, "note" );
        final ItemView view = svc.claims().handoff( actor, key, new Handoff( expectedVersion,
            WorkStatus.fromWire( status ), person ? assignee : null, toQueue ? queue : null, note ) );
        WorkMcpLog.written( TOOL_NAME, "handed-off", key, actor );
        return ok( WorkMcpJson.fullItem( svc, directory, actor, view ) );
    }
}
