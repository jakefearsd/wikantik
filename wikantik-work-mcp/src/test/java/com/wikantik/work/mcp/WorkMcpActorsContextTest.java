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
import com.wikantik.work.WorkService;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What a request's credential becomes in the transport context, with no database: only a verified one rides. */
class WorkMcpActorsContextTest {

    private static HttpServletRequest request( final String attribute, final Object value ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getAttribute( attribute ) ).thenReturn( value );
        return req;
    }

    @Test
    void aRequestWithNoCredentialGivesTheEmptyContextNotNull() {
        final McpTransportContext context = WorkMcpActors.extract( mock( HttpServletRequest.class ) );
        assertNotNull( context );
        assertSame( McpTransportContext.EMPTY, context );
        assertNull( context.get( WorkMcpActors.KEY_RECORD ) );
        assertNull( context.get( WorkMcpActors.AGENT_REF ) );
    }

    @Test
    void anAttributeOfTheWrongTypeIsNotACredential() {
        assertSame( McpTransportContext.EMPTY,
            WorkMcpActors.extract( request( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, "a string, not a record" ) ) );
        assertSame( McpTransportContext.EMPTY,
            WorkMcpActors.extract( request( AgentTokenAuth.ATTR_AGENT_REF, "a string, not an agent ref" ) ) );
    }

    @Test
    void anAgentTokenRidesUnderTheDocumentedKeyAndCarriesNoKeyRecord() {
        final AgentTokenAuth.AgentRef ref = new AgentTokenAuth.AgentRef( 5, 7 );
        final McpTransportContext context = WorkMcpActors.extract( request( AgentTokenAuth.ATTR_AGENT_REF, ref ) );
        assertSame( ref, context.get( WorkMcpActors.AGENT_REF ) );
        assertNull( context.get( WorkMcpActors.KEY_RECORD ) );
        assertEquals( AgentTokenAuth.ATTR_AGENT_REF, WorkMcpActors.AGENT_REF );
    }

    @Test
    void aKeyRecordCarriesNoAgentRef() {
        final ApiKeyService.Record key = WorkMcpFixture.record( 9, "lena", ApiKeyService.Scope.WORK );
        final McpTransportContext context = WorkMcpActors.extract( request( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, key ) );
        assertSame( key, context.get( WorkMcpActors.KEY_RECORD ) );
        assertNull( context.get( WorkMcpActors.AGENT_REF ) );
    }

    @Test
    void noContextAtAllIsNoActorAndTheServiceIsNeverAsked() throws SQLException {
        final WorkService svc = mock( WorkService.class );
        assertNull( WorkMcpActors.actorFor( null, svc ) );
        assertNull( WorkMcpActors.actorFor( McpTransportContext.EMPTY, svc ) );
        verifyNoInteractions( svc );
    }

    @Test
    void aKeyOfAnotherScopeIsNoActorAndNoRegistryLookupHappens() throws SQLException {
        final WorkService svc = mock( WorkService.class );
        final McpTransportContext context = WorkMcpActors.extract( request( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD,
            WorkMcpFixture.record( 9, "lena", ApiKeyService.Scope.MCP_READ ) ) );
        assertNull( WorkMcpActors.actorFor( context, svc ) );
        verifyNoInteractions( svc );
    }
}
