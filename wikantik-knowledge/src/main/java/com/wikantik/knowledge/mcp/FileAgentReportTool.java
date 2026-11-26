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

import com.wikantik.agentreports.AgentReportService;
import com.wikantik.agentreports.FileResult;
import com.wikantik.agentreports.Filer;
import com.wikantik.agentreports.ReportStoreException;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.mcp.McpCallEvent;
import com.wikantik.mcp.tools.ExchangeAwareMcpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The knowledge endpoint's only write: an agent files a short field report about the work it just did. It stores
 * to the agent-report store and never touches pages. The client name, session hash and filing key owner are filled
 * in by the server; whatever the agent passes under those names is ignored.
 */
public final class FileAgentReportTool implements ExchangeAwareMcpTool {

    private static final Logger LOG = LogManager.getLogger( FileAgentReportTool.class );

    public static final String TOOL_NAME = "file_agent_report";

    /** The {@link McpTransportContext} key under which the request's verified API key record travels. */
    static final String KEY_RECORD = ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD;

    private static final String DESCRIPTION =
            "File a short field report about the work you just finished: what you did, what context helped, what was "
            + "missing, and where tools got in your way. This is the endpoint's only write, and it stores to the "
            + "agent-report store only; it never creates or changes wiki pages. Your client name, session and key "
            + "owner are recorded by the server; do not send them. Nothing is truncated: a report over any limit is "
            + "refused with the field named, and every attempt, refused or not, counts toward the hourly limit per "
            + "key (default 60). Limits: task at most 300 characters; context_missing at most 1000; "
            + "friction[].what at most 500; notes at most 2000; workspace at most 100; at most 50 entries in each "
            + "list (products, skills, context_used, friction); each products/skills entry and friction[].product "
            + "and friction[].tool at most 100; context_used[].ref at most 500. "
            + "Required: kind_of_work, task, outcome.";

    private final Supplier< AgentReportService > service;

    /** @param service resolved on each call; may return null while the report store is not configured */
    public FileAgentReportTool( final Supplier< AgentReportService > service ) {
        this.service = service;
    }

    /** The context extractor the knowledge endpoint's transport uses so this tool can see the caller's key. */
    static McpTransportContext extract( final HttpServletRequest request ) {
        final Object record = request.getAttribute( KEY_RECORD );
        return record instanceof ApiKeyService.Record
                ? McpTransportContext.create( Map.of( KEY_RECORD, record ) )
                : McpTransportContext.EMPTY;
    }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "kind_of_work", enumProp( "What kind of work this was.",
                List.of( "operations", "content", "build", "research", "other" ), "build" ) );
        props.put( "task", stringProp( "One line: what you were asked to do (at most 300 characters).",
                "Wire the briefing tool into the session-start hook" ) );
        props.put( "outcome", enumProp( "How it ended.", List.of( "completed", "partial", "abandoned" ), "completed" ) );
        props.put( "workspace", stringProp( "Repository or workspace name (at most 100 characters).", "wikantik" ) );
        props.put( "products", Map.of( "type", "array", "items", Map.of( "type", "string" ),
                "description", "Products touched (at most 50 entries of at most 100 characters)." ) );
        props.put( "skills", Map.of( "type", "array", "items", Map.of( "type", "string" ),
                "description", "Skills you used (at most 50 entries of at most 100 characters)." ) );
        props.put( "window_start", stringProp( "ISO-8601 timestamp with offset: when the work began.",
                "2026-11-26T09:00:00Z" ) );
        props.put( "window_end", stringProp( "ISO-8601 timestamp with offset: when it ended; not before window_start.",
                "2026-11-26T10:15:00Z" ) );
        props.put( "context_used", Map.of( "type", "array", "description",
                "Context you drew on (at most 50 entries).", "items", Map.of( "type", "object",
                "properties", Map.of(
                        "source", enumProp( "Where it came from.", List.of( "wiki", "blog", "repo", "web" ), "wiki" ),
                        "ref", stringProp( "Page name, path or URL (at most 500 characters).", "SimpleAgilityStackHub" ),
                        "helped", enumProp( "Did it help.", List.of( "yes", "partly", "no" ), "yes" ) ),
                "required", List.of( "source", "ref", "helped" ) ) ) );
        props.put( "context_missing", stringProp( "What you needed and could not find (at most 1000 characters).",
                "No page says which port the plane scrapes" ) );
        props.put( "friction", Map.of( "type", "array", "description",
                "Where a tool got in your way (at most 50 entries).", "items", Map.of( "type", "object",
                "properties", Map.of(
                        "product", stringProp( "Product (at most 100 characters).", "wikantik" ),
                        "tool", stringProp( "Tool name (at most 100 characters).", "write_pages" ),
                        "what", stringProp( "What went wrong (at most 500 characters).", "Refused with no reason" ),
                        "blocking", Map.of( "type", "boolean", "description", "True if it stopped you." ) ),
                "required", List.of( "product", "what" ) ) ) );
        props.put( "notes", stringProp( "Anything else worth the owner's time (at most 2000 characters).",
                "The briefing was the most useful call of the session" ) );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( DESCRIPTION )
                .inputSchema( new McpSchema.JsonSchema( "object", props,
                        List.of( "kind_of_work", "task", "outcome" ), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, false, false, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        return error( "a session is required to file a report" );
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments,
                                             final McpSyncServerExchange exchange ) {
        try {
            final AgentReportService svc = service.get();
            if ( svc == null ) {
                return error( "report store unavailable" );
            }
            final String owner = owner( exchange );
            if ( owner == null ) {
                LOG.warn( "file_agent_report refused: no API key owner could be resolved for the call" );
                return error( "this call carries no API key owner; file the report with a personal API key" );
            }
            final Filer filer = new Filer( McpCallEvent.clientName( exchange ), McpCallEvent.sessionHash( exchange ),
                    owner );
            final FileResult result = svc.file( arguments == null ? Map.of() : arguments, filer );
            return render( result );
        } catch ( final ReportStoreException e ) {
            LOG.warn( "file_agent_report: the report store failed: {}", e.getMessage(), e );
            return error( "report store temporarily unavailable; try again later" );
        } catch ( final RuntimeException e ) {
            LOG.warn( "file_agent_report failed unexpectedly: {}", e.getMessage(), e );
            return error( "report could not be filed" );
        }
    }

    private static McpSchema.CallToolResult render( final FileResult result ) {
        if ( result instanceof FileResult.Filed f ) {
            return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, Map.of( "id", f.id(), "filed", true ) );
        }
        if ( result instanceof FileResult.Refused r ) {
            return error( "report refused, nothing stored (this attempt still counts toward the hourly limit): "
                    + String.join( "; ", r.reasons() ) );
        }
        if ( result instanceof FileResult.RateLimited l ) {
            return error( "hourly report limit reached; retry after " + l.retryAfterSeconds() + " seconds" );
        }
        return error( "report could not be filed" );
    }

    /** The login that owns the verified API key of this call, or null when there is none. */
    private static String owner( final McpSyncServerExchange exchange ) {
        final McpTransportContext ctx = exchange == null ? null : exchange.transportContext();
        if ( ctx != null && ctx.get( KEY_RECORD ) instanceof ApiKeyService.Record key ) {
            final String login = key.principalLogin();
            return login == null || login.isBlank() ? null : login;
        }
        return null;
    }

    private static McpSchema.CallToolResult error( final String message ) {
        return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, message );
    }

    private static Map< String, Object > stringProp( final String description, final String example ) {
        return Map.of( "type", "string", "description", description, "examples", List.of( example ) );
    }

    private static Map< String, Object > enumProp( final String description, final List< String > values,
                                                   final String example ) {
        return Map.of( "type", "string", "description", description, "enum", values, "examples", List.of( example ) );
    }
}
