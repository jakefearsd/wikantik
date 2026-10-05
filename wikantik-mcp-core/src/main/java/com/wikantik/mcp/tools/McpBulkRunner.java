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
package com.wikantik.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Heterogeneous bulk-operation runner behind
 * {@link McpToolUtils#runBulk(String, String, Object, int, String, Function, boolean)}.
 */
final class McpBulkRunner {

    private McpBulkRunner() {
    }

    static McpSchema.CallToolResult run( final String toolName,
                                         final String entityLabel,
                                         final Object rawList,
                                         final int bulkLimit,
                                         final String defaultAuthor,
                                         final Function< Map< String, Object >, Map< String, Object > > dispatch,
                                         final boolean isErrorOnAllFailed ) {
        if ( !( rawList instanceof List< ? > list ) || list.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "operations is required and must be a non-empty array" );
        }
        if ( list.size() > bulkLimit ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "bulk limit exceeded: " + list.size() + " > " + bulkLimit );
        }

        final List< Map< String, Object > > succeeded = new ArrayList<>();
        final List< Map< String, Object > > failed = new ArrayList<>();

        for ( final Object opEl : list ) {
            if ( !( opEl instanceof Map< ?, ? > opMap ) ) {
                failed.add( Map.of( "error", "operation must be an object" ) );
                continue;
            }
            final Map< String, Object > op = McpToolUtils.castStringKey( opMap );
            final String tag = McpToolUtils.stringOrNull( op.get( "tag" ) );
            final String action = McpToolUtils.stringOrNull( op.get( "action" ) );

            final Map< String, Object > result = dispatch.apply( op );
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "tag", tag );
            entry.put( "action", action );
            entry.putAll( result );
            if ( entry.containsKey( "error" ) ) failed.add( entry );
            else succeeded.add( entry );
        }

        McpAudit.logBulkWrite( toolName, list.size(), succeeded.size(), failed.size(), defaultAuthor );

        final boolean allFailed = succeeded.isEmpty() && !failed.isEmpty();
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "status", allFailed ? "failed" : "completed" );
        out.put( "succeeded", succeeded );
        out.put( "failed", failed );
        out.put( "message", succeeded.size() + " of " + list.size() + " " + entityLabel + " operations applied" );
        return McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent( McpToolUtils.SHARED_GSON.toJson( out ) ) ) )
                .structuredContent( out )
                .isError( isErrorOnAllFailed && allFailed )
                .build();
    }
}
