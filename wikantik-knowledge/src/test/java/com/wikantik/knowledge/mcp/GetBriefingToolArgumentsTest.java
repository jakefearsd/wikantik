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

import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingLogService;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.briefing.ScopeMode;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.querylog.QueryLogService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** How {@code get_briefing} turns raw MCP arguments into a {@link BriefingRequest}, and its degrade paths. */
class GetBriefingToolArgumentsTest {

    private BriefingAssemblyService svc;

    @BeforeEach
    void setUp() {
        svc = mock( BriefingAssemblyService.class );
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenReturn( new ContextBriefing( "p", List.of(),
                new BundleCoverage( 0, 0, 0.0, BundleCoverage.UNKNOWN ), List.of(), List.of(), 4000, 0 ) );
    }

    private GetBriefingTool tool() {
        return new GetBriefingTool( svc, () -> null, () -> null, PageViewGate.ALLOW_ALL );
    }

    private BriefingRequest sent() {
        final ArgumentCaptor< BriefingRequest > c = ArgumentCaptor.forClass( BriefingRequest.class );
        verify( svc ).assemble( c.capture() );
        return c.getValue();
    }

    private static String text( final McpSchema.CallToolResult r ) {
        return ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    }

    @Test
    void aNumericBudgetIsPassedThroughAsTokens() {
        tool().execute( Map.of( "prompt", "x", "budget", 1234 ) );
        assertEquals( 1234, sent().budgetTokens() );
    }

    @Test
    void aBudgetThatIsNotANumberIsIgnored() {
        tool().execute( Map.of( "prompt", "x", "budget", "1234" ) );
        assertNull( sent().budgetTokens() );
    }

    @Test
    void noBudgetMeansTheServiceDefault() {
        tool().execute( Map.of( "prompt", "x" ) );
        assertNull( sent().budgetTokens() );
    }

    @Test
    void pinsAndClustersAreTrimmedAndBlankOrNullEntriesDropped() {
        tool().execute( Map.of( "pins", Arrays.asList( " A ", null, "  ", "B" ), "clusters", Arrays.asList( "c1", "" ) ) );
        final BriefingRequest r = sent();
        assertEquals( List.of( "A", "B" ), r.pins() );
        assertEquals( List.of( "c1" ), r.clusters() );
    }

    @Test
    void anEmptyPinListWithNothingElseIsRefused() {
        final var result = tool().execute( Map.of( "pins", List.of(), "prompt", "   " ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "at least one of pins, clusters, prompt is required" ), text( result ) );
        verifyNoInteractions( svc );
    }

    @Test
    void anUnknownScopeModeIsRefusedWithoutAssembling() {
        final var result = tool().execute( Map.of( "prompt", "x", "scope_mode", "sideways" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "Unknown scope_mode" ), text( result ) );
        verifyNoInteractions( svc );
    }

    @Test
    void scopeModeStrictReachesTheService() {
        tool().execute( Map.of( "prompt", "x", "scope_mode", "STRICT" ) );
        assertEquals( ScopeMode.STRICT, sent().scopeMode() );
    }

    @Test
    void nullLogSuppliersAndANullGateAreTolerated() {
        final var result = new GetBriefingTool( svc, null, null, null ).execute( Map.of( "prompt", "x" ) );
        assertFalse( Boolean.TRUE.equals( result.isError() ), text( result ) );
        assertTrue( text( result ).contains( "# Wiki context briefing" ), text( result ) );
    }

    @Test
    void suppliersYieldingNoLogServiceAreTolerated() {
        final QueryLogService none = null;
        final BriefingLogService noBriefingLog = null;
        final var result = new GetBriefingTool( svc, () -> none, () -> noBriefingLog, PageViewGate.ALLOW_ALL )
                .execute( Map.of( "prompt", "x" ) );
        assertFalse( Boolean.TRUE.equals( result.isError() ), text( result ) );
    }

    @Test
    void aServiceFailureCarriesItsMessageInTheErrorResult() {
        when( svc.assemble( any( BriefingRequest.class ) ) ).thenThrow( new IllegalStateException( "retrieval offline" ) );
        final var result = tool().execute( Map.of( "prompt", "x" ) );
        assertTrue( result.isError() );
        assertTrue( text( result ).contains( "retrieval offline" ), text( result ) );
    }
}
