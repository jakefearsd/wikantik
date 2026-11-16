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
import com.wikantik.work.WorkClaimsService.ClaimResult;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/**
 * Takes an item for the calling agent, or renews the agent's own claim on it. The answer carries the claim's expiry
 * and the item in full, so one call gives the agent what it needs to start.
 */
public final class ClaimItemTool extends WorkMcpTool {

    public static final String TOOL_NAME = "claim_item";

    private static final Set< String > ALLOWED = Set.of( "key" );

    private final WorkService svc;
    private final WorkDirectory directory;

    public ClaimItemTool( final WorkService svc, final WorkDirectory directory ) {
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
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Claim an item as the agent you are, or renew your own claim by calling this again before "
                + "expiresAt. Returns {claim:{expiresAt,renewed},item:<as get_item>}. A claim you abandon blocks the "
                + "item until it expires: finish with handoff_item or release_item. Refusals: not_claimable: (it "
                + "names who holds it and until when), denied:, archived:." )
            .inputSchema( schema( Map.of( "key", prop( "string", "The item key.", "PLAT-12" ) ), List.of( "key" ) ) )
            .annotations( WRITES )
            .build();
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, ALLOWED, Map.of() );
        final String key = itemKey( args );
        final ClaimResult result = svc.claims().claim( actor, key );
        WorkMcpLog.written( TOOL_NAME, result.renewed() ? "renewed" : "claimed", key, actor );
        final Map< String, Object > claim = new LinkedHashMap<>();
        claim.put( "expiresAt", result.view().item().claimExpiresAt() == null ? null
            : result.view().item().claimExpiresAt().toString() );
        claim.put( "renewed", result.renewed() );
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "claim", claim );
        out.put( "item", WorkMcpJson.fullItem( svc, directory, actor, result.view() ) );
        return ok( out );
    }
}
