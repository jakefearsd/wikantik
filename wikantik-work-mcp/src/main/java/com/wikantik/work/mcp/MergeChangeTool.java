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
import java.util.Map;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/** Reports that a change was merged: the reviewer's step, after it approved the change's current commit. */
public final class MergeChangeTool extends WorkMcpTool {

    public static final String TOOL_NAME = "merge_change";

    private static final ToolSpec SPEC = ToolSpec.writes( TOOL_NAME )
        .description( "Report a change merged, after you merged it on the code host. Only the agent whose "
            + "approval stands on the change's current commit may, and never an agent that opened it or put a commit "
            + "on it. Returns "
            + "the change. Refusals: denied: (not the approving reviewer, your own change, or a level where a "
            + "person merges), not_approved: (no approval stands on the change's current commit), change_closed:." )
        .requiredArg( "change", "integer", "The change's id.", 7 )
        .requiredArg( "merged_sha", "string", "The commit the merge produced on the code host (a squash or merge commit is fine).", "4f2a9c1" );

    private final WorkService svc;
    private final WorkDirectory directory;

    public MergeChangeTool( final WorkService svc, final WorkDirectory directory ) {
        super( SPEC );
        this.svc = svc;
        this.directory = directory;
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, allowed(), Map.of() );
        final long id = WorkMcpChanges.id( args );
        svc.changes().merge( actor, id, requiredString( args, "merged_sha" ) );
        WorkMcpLog.written( TOOL_NAME, "change-merged", "change:" + id, actor );
        return ok( WorkMcpChanges.one( svc, directory, actor, id ) );
    }
}
