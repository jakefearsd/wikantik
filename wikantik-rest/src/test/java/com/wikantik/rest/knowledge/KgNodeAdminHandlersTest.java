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
package com.wikantik.rest.knowledge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.wikantik.HttpMockFactory;
import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KnowledgeGraphService;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted test for {@link KgNodeAdminHandlers#handleDeleteNode} error path — not otherwise
 * exercised by {@code AdminKnowledgeResourceTest}/{@code AdminKnowledgeResourceHandlerCoverageTest}
 * (which drive this class through {@code AdminKnowledgeResource}'s dispatch table and don't cover
 * the curation-ops-refuses-delete branch).
 */
class KgNodeAdminHandlersTest {

    private final Gson gson = new Gson();

    @Test
    void handleDeleteNode_returns500WhenCurationOpsRefuses() throws Exception {
        final KgCurationOps ops = Mockito.mock( KgCurationOps.class );
        final UUID id = UUID.randomUUID();
        Mockito.when( ops.tryDeleteNode( id, null ) )
                .thenReturn( Optional.of( "node has 3 dependent edges; delete those first" ) );
        final KgNodeAdminHandlers handlers = new KgNodeAdminHandlers( () -> ops, () -> null, () -> null, () -> null );
        final KnowledgeGraphService service = Mockito.mock( KnowledgeGraphService.class );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        handlers.handleDeleteNode( service, response, id.toString() );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( "node has 3 dependent edges; delete those first", obj.get( "message" ).getAsString() );
    }
}
