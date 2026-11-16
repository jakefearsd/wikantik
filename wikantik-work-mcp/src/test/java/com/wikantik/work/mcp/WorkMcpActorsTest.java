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

import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.Actor;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who is calling comes only from the key on the request, through the one place that derives an actor from a key. */
@RequiresPostgres
class WorkMcpActorsTest {

    private WorkMcpFixture f;

    @BeforeEach
    void setUp() throws SQLException {
        f = new WorkMcpFixture();
    }

    private static HttpServletRequest requestWith( final ApiKeyService.Record record ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) ).thenReturn( record );
        return req;
    }

    @Test
    void noRecordIsNoActor() throws SQLException {
        assertNull( WorkMcpActors.actorFor( McpTransportContext.EMPTY, f.svc ) );
        assertNull( WorkMcpActors.actorFor( WorkMcpActors.extract( mock( HttpServletRequest.class ) ), f.svc ),
            "a request that carries no key record gives an empty context" );
    }


    @Test
    void extractCarriesTheRecordUnderTheDocumentedKey() {
        final ApiKeyService.Record key = WorkMcpFixture.record( 9, "lena", ApiKeyService.Scope.WORK );
        assertSame( key, WorkMcpActors.extract( requestWith( key ) ).get( WorkMcpActors.KEY_RECORD ) );
        assertEquals( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, WorkMcpActors.KEY_RECORD );
    }

    @Test
    void aKeyOfAnotherScopeIsNoActor() throws SQLException {
        final ApiKeyService.Record key = WorkMcpFixture.record( 9_001, "lena", ApiKeyService.Scope.MCP_READ );
        assertNull( WorkMcpActors.actorFor( WorkMcpActors.extract( requestWith( key ) ), f.svc ) );
    }

    @Test
    void anUnboundWorkKeyIsItsOwnerAsAPersonNeverAdmin() throws SQLException {
        final ApiKeyService.Record key = WorkMcpFixture.record( ( int ) f.apiKey( "work" ), "lena", ApiKeyService.Scope.WORK );

        final Actor actor = WorkMcpActors.actorFor( WorkMcpActors.extract( requestWith( key ) ), f.svc );

        assertEquals( Actor.Kind.HUMAN, actor.kind() );
        assertEquals( "lena", actor.login() );
        assertFalse( actor.admin() );
    }


    // ---- agent tokens (phase 3a)

    private static HttpServletRequest requestWith( final com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef ref ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getAttribute( com.wikantik.auth.apikeys.AgentTokenAuth.ATTR_AGENT_REF ) ).thenReturn( ref );
        return req;
    }

    @Test
    void anAgentRefResolvesToThatAgentWithItsSponsor() throws SQLException {
        final Actor actor = WorkMcpActors.actorFor( WorkMcpActors.extract( requestWith(
            new com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef( 5, f.builder.id() ) ) ), f.svc );
        assertNotNull( actor );
        assertEquals( Actor.Kind.AGENT, actor.kind() );
        assertEquals( f.builder.id(), actor.agentId() );
        assertEquals( "admin", actor.sponsor().login(), "the registrant stands behind it" );
    }

    @Test
    void anAgentRefForARetiredAgentIsNoActor() throws SQLException {
        f.svc.registry().retireAgent( f.admin, f.builder.id() );
        assertNull( WorkMcpActors.actorFor( WorkMcpActors.extract( requestWith(
            new com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef( 5, f.builder.id() ) ) ), f.svc ) );
    }
}
