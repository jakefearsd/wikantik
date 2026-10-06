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
package com.wikantik.auth.apikeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.auth.apikeys.ApiKeyService.Scope;
import org.junit.jupiter.api.Test;

/** The MCP scope hierarchy: MCP_READ ⊂ MCP (admin), with TOOLS/ALL orthogonal. */
class ApiKeyScopeTest {

    @Test
    void mcpFamilyIsHierarchical() {
        // A higher-privilege key satisfies a lower-privilege requirement.
        assertTrue( Scope.MCP_ADMIN.matches( Scope.MCP_READ ) );
        assertTrue( Scope.MCP_ADMIN.matches( Scope.MCP_ADMIN ) );
        assertTrue( Scope.MCP_READ.matches( Scope.MCP_READ ) );
    }

    @Test
    void lowerScopeDoesNotSatisfyHigher() {
        assertFalse( Scope.MCP_READ.matches( Scope.MCP_ADMIN ), "read key must not reach the admin endpoint/tools" );
    }

    @Test
    void allCoversEverythingAndToolsIsOrthogonal() {
        for ( final Scope required : Scope.values() ) {
            assertTrue( Scope.ALL.matches( required ), "ALL must cover " + required );
        }
        // TOOLS (OpenAPI /tools/*) is a separate surface: neither direction crosses into MCP.
        assertTrue( Scope.TOOLS.matches( Scope.TOOLS ) );
        assertFalse( Scope.TOOLS.matches( Scope.MCP_READ ) );
        assertFalse( Scope.MCP_ADMIN.matches( Scope.TOOLS ) );
        assertFalse( Scope.MCP_READ.matches( Scope.TOOLS ) );
    }

    @Test
    void wireValuesRoundTripAndPreServeLegacyMcp() {
        assertEquals( Scope.MCP_ADMIN, Scope.fromWire( "mcp" ), "legacy 'mcp' keys stay full-admin" );
        assertEquals( Scope.MCP_READ, Scope.fromWire( "mcp_read" ) );
        assertEquals( Scope.TOOLS, Scope.fromWire( "tools" ) );
        assertEquals( Scope.ALL, Scope.fromWire( "all" ) );
        assertEquals( Scope.ALL, Scope.fromWire( null ) );
    }

    @Test
    void adminScopeWireIsMcpAdminAndMcpIsALegacyAlias() {
        assertEquals( "mcp_admin", Scope.MCP_ADMIN.wire() );
        assertEquals( Scope.MCP_ADMIN, Scope.fromWire( "mcp_admin" ) );
        assertTrue( Scope.isLegacyWire( "mcp" ) );
        assertTrue( Scope.isLegacyWire( " MCP " ) );
        assertFalse( Scope.isLegacyWire( "mcp_admin" ) );
        assertFalse( Scope.isLegacyWire( null ) );
        assertEquals( "mcp_read, mcp_admin, tools, all", Scope.validWireNames(),
                "error messages advertise only the current names" );
    }
}
