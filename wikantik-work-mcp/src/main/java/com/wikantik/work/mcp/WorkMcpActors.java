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

import com.wikantik.auth.apikeys.AgentTokenAuth;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.work.Actor;
import com.wikantik.work.WorkService;
import com.wikantik.work.runtime.WikiWorkActors;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;

import java.sql.SQLException;
import java.util.Map;

/**
 * Who is calling a work tool: the API key on that call's own HTTP request, and nothing else.
 *
 * <p>The MCP SDK calls the transport's context extractor once per HTTP request, and hands that request's context to
 * that request's tool call, so two requests on one long-lived MCP session carry their own keys. The key is turned into
 * an actor on <em>every</em> call (never cached with the session): an agent retired, or a key rotated, in the middle
 * of a session loses access on its next call. There is no fallback to the key's owner, a session, a guest or a
 * default actor.
 */
public final class WorkMcpActors {

    /**
     * The {@link McpTransportContext} key under which the request's verified {@link ApiKeyService.Record} rides: the
     * same name as the request attribute the shared access filter sets it under.
     */
    public static final String KEY_RECORD = ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD;

    /** Context key under which a verified agent token's {@link AgentTokenAuth.AgentRef} travels to the tool call. */
    public static final String AGENT_REF = AgentTokenAuth.ATTR_AGENT_REF;

    private WorkMcpActors() {
    }

    /**
     * The transport context for one HTTP request: the key record the access filter verified, or an empty context
     * when the request carries none.
     */
    public static McpTransportContext extract( final HttpServletRequest request ) {
        final Object record = request.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD );
        if ( record instanceof ApiKeyService.Record ) {
            return McpTransportContext.create( Map.of( KEY_RECORD, record ) );
        }
        final Object agent = request.getAttribute( AgentTokenAuth.ATTR_AGENT_REF );
        if ( agent instanceof AgentTokenAuth.AgentRef ) {
            return McpTransportContext.create( Map.of( AGENT_REF, agent ) );
        }
        return McpTransportContext.EMPTY;
    }

    /**
     * The actor the context's key stands for: the agent it is bound to, or its owner as a person for a key never bound
     * to an agent.
     *
     * @return the actor, or {@code null} when the context carries no key, or the key is not a work key, or is bound to
     *         a retired agent, or was once an agent's key and has been rotated away
     */
    public static Actor actorFor( final McpTransportContext context, final WorkService service ) throws SQLException {
        if ( context == null ) {
            return null;
        }
        if ( context.get( AGENT_REF ) instanceof AgentTokenAuth.AgentRef agent ) {
            return WikiWorkActors.actorForAgent( service, agent );
        }
        if ( !( context.get( KEY_RECORD ) instanceof ApiKeyService.Record key ) ) {
            return null;
        }
        return WikiWorkActors.actorForKey( service, key );
    }
}
