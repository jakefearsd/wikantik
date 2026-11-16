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
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkService.ItemView;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/** Gives up the calling agent's claim on an item, returning it to where it was waiting. */
public final class ReleaseItemTool extends WorkMcpTool {

    public static final String TOOL_NAME = "release_item";

    private static final Set< String > ALLOWED = Set.of( "key", "note" );

    private final WorkService svc;

    public ReleaseItemTool( final WorkService svc ) {
        this.svc = svc;
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
        props.put( "note", prop( "string", "Why you are letting go of it; recorded in the item's history.",
            "Blocked on a missing credential." ) );
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Release your claim on an item you cannot finish, so someone else can take it. Returns "
                + "{key,status}. Refusals: not_claimed: (you hold no claim), denied: (another agent holds it)." )
            .inputSchema( schema( props, List.of( "key" ) ) )
            .annotations( WRITES )
            .build();
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, ALLOWED, Map.of() );
        final String key = itemKey( args );
        final String note = stringArg( args, "note" );
        final ItemView view = svc.claims().release( actor, key, note );
        WorkMcpLog.written( TOOL_NAME, "released", key, actor );
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "key", view.item().key() );
        out.put( "status", view.item().status().wire() );
        return ok( out );
    }
}
