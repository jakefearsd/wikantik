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
import com.wikantik.work.WorkValidationException;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.Map;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/** Moves a change along: a new commit, a review link, asking for a review, or giving the change up. */
public final class UpdateChangeTool extends WorkMcpTool {

    public static final String TOOL_NAME = "update_change";

    private static final String IN_REVIEW = "in_review";
    private static final String ABANDONED = "abandoned";

    private static final ToolSpec SPEC = ToolSpec.writes( TOOL_NAME )
        .description( "Update a change you are working on: its newest commit, its review link (an empty "
            + "review_url removes it), or its state (in_review, abandoned). Returns the change. Refusals: change_closed: (already merged or "
            + "abandoned), denied: (another agent holds the item)." )
        .requiredArg( "change", "integer", "The change's id, from open_change or get_item.", 7 )
        .arg( "head_sha", "string", "The branch's newest commit. A new commit clears any approval.",
            "4f2a9c1" )
        .arg( "review_url", "string", "A link to the review.", "https://example.com/pull/7" )
        .arg( "first_commit_at", "string", "The time of the branch's first commit, ISO-8601; only moves earlier.",
            "2026-03-01T09:30:00Z" )
        .arg( "state", "string", "in_review to ask for a review, abandoned to give the change up.",
            IN_REVIEW )
        .arg( "note", "string", "With abandoned: why.", "Superseded by a smaller change." );

    private final WorkService svc;
    private final WorkDirectory directory;

    public UpdateChangeTool( final WorkService svc, final WorkDirectory directory ) {
        super( SPEC );
        this.svc = svc;
        this.directory = directory;
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, allowed(), Map.of() );
        final long id = WorkMcpChanges.id( args );
        final String head = stringArg( args, "head_sha" );
        final String url = stringArg( args, "review_url" );
        final String state = stringArg( args, "state" );
        final String first = stringArg( args, "first_commit_at" );
        requireFitting( head != null || url != null || first != null, state, stringArg( args, "note" ) );
        String action = "change-updated";
        if ( head != null || url != null || first != null ) {
            svc.changes().update( actor, id, head, url, first );
        }
        if ( IN_REVIEW.equals( state ) ) {
            svc.changes().requestReview( actor, id );
            action = "review-requested";
        } else if ( ABANDONED.equals( state ) ) {
            svc.changes().abandon( actor, id, stringArg( args, "note" ) );
            action = "change-abandoned";
        }
        WorkMcpLog.written( TOOL_NAME, action, "change:" + id, actor );
        return ok( WorkMcpChanges.one( svc, directory, actor, id ) );
    }

    /** What the call names must make sense together, said by name before anything is written. */
    private static void requireFitting( final boolean names, final String state, final String note ) {
        if ( !names && state == null ) {
            throw new WorkValidationException( "change", "Say what to change: head_sha, review_url or state." );
        }
        if ( state != null && !IN_REVIEW.equals( state ) && !ABANDONED.equals( state ) ) {
            throw new WorkValidationException( "state", "Use review_change or merge_change." );
        }
        if ( note != null && !ABANDONED.equals( state ) ) {
            // refused by name, so an agent never believes it left a note that was dropped
            throw new WorkValidationException( "note", "A note goes with state abandoned." );
        }
    }
}
