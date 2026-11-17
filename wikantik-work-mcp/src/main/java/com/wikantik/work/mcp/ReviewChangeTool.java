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
import java.util.Set;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/** Records a review of a change: an approval of its current commit, or a request for more work. */
public final class ReviewChangeTool extends WorkMcpTool {

    public static final String TOOL_NAME = "review_change";
    private static final Set< String > ALLOWED = Set.of( "change", "approved", "note" );

    private final WorkService svc;
    private final WorkDirectory directory;

    public ReviewChangeTool( final WorkService svc, final WorkDirectory directory ) {
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
        props.put( "change", prop( "integer", "The change's id.", 7 ) );
        props.put( "approved", prop( "boolean", "true to approve the change at its current commit; false to send "
            + "it back.", true ) );
        props.put( "note", prop( "string", "What you found; required in practice when you send it back.",
            "The new route has no test." ) );
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Review a change that is in review. An approval is for the commit the change is at now: a "
                + "later commit needs a new review. You may not review a change you opened. Returns the change. "
                + "Refusals: denied: (your own change, or another agent holds the item), not_in_review:, "
                + "change_closed:." )
            .inputSchema( schema( props, List.of( "change", "approved" ) ) )
            .annotations( WRITES )
            .build();
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, ALLOWED, Map.of() );
        final long id = WorkMcpChanges.id( args );
        final boolean approved = WorkMcpChanges.requiredBoolean( args, "approved" );
        svc.changes().review( actor, id, approved, stringArg( args, "note" ) );
        WorkMcpLog.written( TOOL_NAME, approved ? "change-approved" : "changes-requested", "change:" + id, actor );
        return ok( WorkMcpChanges.one( svc, directory, actor, id ) );
    }
}
