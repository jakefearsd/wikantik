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
package com.wikantik.knowledge.mcp;

import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * GoF Template Method — owns the error envelope once. All 19 {@code *Tool}
 * classes on {@code /knowledge-mcp} previously hand-copied an outer
 * try/catch in {@code execute()} (with wording drift across copies — some
 * logged at WARN, some at ERROR, some caught {@code RuntimeException}
 * instead of {@code Exception}) plus the same read-only
 * {@link McpSchema.ToolAnnotations} literal in {@code definition()}.
 * Subclasses implement only {@link #doExecute(Map)}; this class supplies
 * the uniform error logging + {@link McpToolUtils#errorResult} envelope
 * and the shared annotations constant.
 */
abstract class AbstractKnowledgeMcpTool implements McpTool {

    private final Logger log = LogManager.getLogger( getClass() );

    /** Read-only knowledge-surface annotations shared by every tool on this MCP endpoint. */
    protected static final McpSchema.ToolAnnotations READ_ONLY_ANNOTATIONS =
            new McpSchema.ToolAnnotations( null, true, false, true, null, null );

    @Override
    public final McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            return doExecute( arguments );
        } catch ( final Exception e ) {
            log.error( "{} failed: {}", name(), e.getMessage(), e );
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, e.getMessage() );
        }
    }

    protected abstract McpSchema.CallToolResult doExecute( Map< String, Object > arguments ) throws Exception;
}
