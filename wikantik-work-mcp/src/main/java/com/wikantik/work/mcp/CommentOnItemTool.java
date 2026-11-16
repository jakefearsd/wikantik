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
import com.wikantik.work.CommentView;
import com.wikantik.work.WorkService;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds a comment, as the calling agent, to an item. Mentions follow whatever the service accepts from a non-admin. */
public final class CommentOnItemTool extends WorkMcpTool {

    public static final String TOOL_NAME = "comment_on_item";

    private static final Set< String > ALLOWED = Set.of( "key", "body" );

    private final WorkService svc;

    public CommentOnItemTool( final WorkService svc ) {
        this.svc = svc;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "key", prop( "string", "The item key.", "PLAT-12" ) );
        props.put( "body", prop( "string", "The comment, Markdown source. Mention a person by display name "
            + "token as the service accepts.", "Tests added; waiting on review." ) );
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Comment on an item as the agent you are. Returns {id,at}. Refusals: denied:, archived:, "
                + "or body: when the comment is empty or too long." )
            .inputSchema( schema( props, List.of( "key", "body" ) ) )
            .annotations( WRITES )
            .build();
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, ALLOWED, Map.of() );
        final String key = itemKey( args );
        final String body = requiredString( args, "body" );
        final CommentView comment = svc.addComment( actor, key, body );
        McpAudit.logWrite( TOOL_NAME, "commented", key, actor.login() );
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "id", comment.id() );
        out.put( "at", comment.createdAt() == null ? null : comment.createdAt().toString() );
        return ok( out );
    }
}
