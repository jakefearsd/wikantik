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
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * GoF Template Method — owns the outer error envelope once, shared by every
 * MCP tool on both {@code /knowledge-mcp} and {@code /wikantik-admin-mcp}.
 *
 * <p>Originally {@code com.wikantik.knowledge.mcp.AbstractKnowledgeMcpTool}, used only by
 * the 19 read-only knowledge-mcp tools. Promoted to {@code wikantik-mcp-core} so the 24
 * admin-mcp tool classes — which had each hand-copied (or, in several cases, entirely
 * omitted) the same outer try/catch — can share it too. Before this promotion a handful
 * of admin-mcp tools (e.g. {@code get_wiki_stats}, {@code get_backlinks}) had no outer
 * catch at all, so an unexpected {@link RuntimeException} escaped the structured
 * {@code {error, suggestion}} envelope entirely instead of coming back as a normal MCP
 * tool error.</p>
 *
 * <p>Subclasses implement only {@link #doExecute(Map)}; this class supplies the uniform
 * error logging + {@link McpToolUtils#errorResult(Gson, String)} envelope and the shared
 * read-only annotations constant. Inner, more specific catches (e.g. a per-item catch in
 * a bulk operation, or a catch that turns a known exception into a friendlier message
 * with a {@code suggestion}) are unaffected — they still live in {@link #doExecute} and
 * only truly unexpected exceptions reach this class's outer catch.</p>
 *
 * <p>The two MCP endpoints serialize with slightly different {@link Gson} instances (the
 * knowledge-mcp one adds {@code Instant}/{@code Optional} type adapters the admin-mcp one
 * doesn't need). Override {@link #gson()} to supply a module-specific instance; the
 * default is {@link McpToolUtils#SHARED_GSON}, matching every hand-rolled admin-mcp catch
 * block this class replaces.</p>
 */
public abstract class AbstractMcpTool implements McpTool {

    private final Logger log = LogManager.getLogger( getClass() );

    /** Read-only annotations shared by every read-only tool on either MCP endpoint. */
    protected static final McpSchema.ToolAnnotations READ_ONLY_ANNOTATIONS =
            new McpSchema.ToolAnnotations( null, true, false, true, null, null );

    @Override
    public final McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            return doExecute( arguments );
        } catch ( final Exception e ) {
            log.error( "{} failed: {}", name(), e.getMessage(), e );
            return McpToolUtils.errorResult( gson(), e.getMessage() );
        }
    }

    /**
     * The {@link Gson} instance used to serialize the outer-catch error envelope.
     * Defaults to {@link McpToolUtils#SHARED_GSON}; override when a module needs a
     * Gson instance configured with additional type adapters.
     */
    protected Gson gson() {
        return McpToolUtils.SHARED_GSON;
    }

    protected abstract McpSchema.CallToolResult doExecute( Map< String, Object > arguments ) throws Exception;
}
