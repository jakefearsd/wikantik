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
import com.wikantik.work.Change;
import com.wikantik.work.ChangeDraft;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.Map;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/** Records a branch of work on an item: open, or already merged when the work went straight to the base branch. */
public final class OpenChangeTool extends WorkMcpTool {

    public static final String TOOL_NAME = "open_change";

    private static final ToolSpec SPEC = ToolSpec.writes( TOOL_NAME )
        .description( "Record a branch of work on an item. Returns the change, with the id the other change "
            + "tools take. Refusals: denied: (another agent holds the item; or a direct commit nobody allowed), "
            + "productKey: (the item targets no product), branch: (the item already has a live change on it), "
            + "status: (the item is closed)." )
        .requiredArg( "key", "string", "The item key.", "PLAT-12" )
        .requiredArg( "branch", "string", "The branch the work is on. Name it work/<ITEM-KEY>-<slug>.",
            "work/PLAT-12-spa-route" )
        .arg( "base_branch", "string", "The branch it will merge into; the product's default when omitted.",
            "main" )
        .arg( "head_sha", "string", "The branch's newest commit.", "4f2a9c1" )
        .arg( "review_url", "string", "A link to the review, when there is one.",
            "https://example.com/pull/7" )
        .arg( "merged_sha", "string", "Only when the work is already on the base branch: its commit. The "
            + "change is then recorded as merged directly, which needs a lead to have allowed direct commits.",
            "9b01c77" );

    private final WorkService svc;
    private final WorkDirectory directory;

    public OpenChangeTool( final WorkService svc, final WorkDirectory directory ) {
        super( SPEC );
        this.svc = svc;
        this.directory = directory;
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, allowed(), Map.of() );
        final String key = itemKey( args );
        final Change c = svc.changes().open( actor, key, new ChangeDraft( requiredString( args, "branch" ),
            stringArg( args, "base_branch" ), stringArg( args, "head_sha" ), stringArg( args, "review_url" ),
            stringArg( args, "merged_sha" ) ) );
        WorkMcpLog.written( TOOL_NAME, c.mergedSha() == null ? "change-opened" : "change-merged-directly", key, actor );
        return ok( WorkMcpChanges.one( svc, directory, actor, c.id() ) );
    }
}
