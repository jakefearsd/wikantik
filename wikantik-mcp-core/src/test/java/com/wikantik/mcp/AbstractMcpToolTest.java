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
package com.wikantik.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractMcpToolTest {

    private static String textOf( final McpSchema.CallToolResult result ) {
        return ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
    }

    /** A tool whose doExecute() returns whatever result is handed to the constructor. */
    private static final class FixedResultTool extends AbstractMcpTool {
        private final McpSchema.CallToolResult toReturn;

        FixedResultTool( final McpSchema.CallToolResult toReturn ) {
            this.toReturn = toReturn;
        }

        @Override
        public String name() { return "fixed_result_tool"; }

        @Override
        public McpSchema.Tool definition() {
            return McpSchema.Tool.builder().name( name() )
                    .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                    .build();
        }

        @Override
        protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) {
            return toReturn;
        }
    }

    /** A tool whose doExecute() always throws, to exercise the outer-catch envelope. */
    private static final class ThrowingTool extends AbstractMcpTool {
        private final RuntimeException toThrow;

        ThrowingTool( final RuntimeException toThrow ) {
            this.toThrow = toThrow;
        }

        @Override
        public String name() { return "throwing_tool"; }

        @Override
        public McpSchema.Tool definition() {
            return McpSchema.Tool.builder().name( name() )
                    .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                    .build();
        }

        @Override
        protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) throws Exception {
            throw toThrow;
        }
    }

    /** A tool that overrides gson() to prove subclasses control the outer-catch serializer. */
    private static final class CustomGsonThrowingTool extends AbstractMcpTool {
        static final Gson CUSTOM_GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

        @Override
        public String name() { return "custom_gson_tool"; }

        @Override
        public McpSchema.Tool definition() {
            return McpSchema.Tool.builder().name( name() )
                    .inputSchema( new McpSchema.JsonSchema( "object", Map.of(), List.of(), null, null, null ) )
                    .build();
        }

        @Override
        protected Gson gson() { return CUSTOM_GSON; }

        @Override
        protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) {
            throw new IllegalStateException( "always fails" );
        }
    }

    @Test
    void executeReturnsDoExecuteResultOnSuccess() {
        final McpSchema.CallToolResult ok = McpSchema.CallToolResult.builder()
                .content( List.of( new McpSchema.TextContent( "{\"ok\":true}" ) ) )
                .build();
        final AbstractMcpTool tool = new FixedResultTool( ok );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        assertFalse( Boolean.TRUE.equals( result.isError() ) );
        assertTrue( textOf( result ).contains( "\"ok\":true" ) );
    }

    @Test
    void executeCatchesExceptionAndReturnsErrorEnvelope() {
        final AbstractMcpTool tool = new ThrowingTool( new RuntimeException( "kaboom" ) );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        assertTrue( result.isError() );
        assertTrue( textOf( result ).contains( "kaboom" ) );
    }

    @Test
    void executeCatchesExceptionWithNullMessage() {
        final AbstractMcpTool tool = new ThrowingTool( new NullPointerException() );

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        assertTrue( result.isError() );
        // null-message exceptions still produce a non-crashing structured envelope
        final Object error = ( ( Map< ?, ? > ) result.structuredContent() ).get( "error" );
        assertEquals( "internal error (see server log)", error );
    }

    @Test
    void defaultGsonIsSharedGson() {
        final AbstractMcpTool tool = new ThrowingTool( new RuntimeException( "x" ) );
        // Exercised indirectly: SHARED_GSON serializes nulls, so a null suggestion-less
        // error body still round-trips through the default gson() without throwing.
        final McpSchema.CallToolResult result = tool.execute( Map.of() );
        assertTrue( result.isError() );
    }

    @Test
    void subclassCanOverrideGsonForOuterCatchEnvelope() {
        final AbstractMcpTool tool = new CustomGsonThrowingTool();

        final McpSchema.CallToolResult result = tool.execute( Map.of() );

        assertTrue( result.isError() );
        // The custom Gson pretty-prints, so the serialized body contains newlines/indentation
        // that McpToolUtils.SHARED_GSON (compact) would never produce.
        assertTrue( textOf( result ).contains( "\n" ), "expected pretty-printed JSON from the overridden gson()" );
    }

    @Test
    void readOnlyAnnotationsConstantIsReadOnlyAndNonDestructive() {
        assertTrue( AbstractMcpTool.READ_ONLY_ANNOTATIONS.readOnlyHint() );
        assertFalse( Boolean.TRUE.equals( AbstractMcpTool.READ_ONLY_ANNOTATIONS.destructiveHint() ) );
    }
}
