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
package com.wikantik.its.mcp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * #104 on the MCP write tools, end to end: {@code update_page} and {@code write_pages} refuse a write that adds an
 * access rule the wiki cannot read (it would lock the page to administrators), and the refusal cites the reason —
 * {@code ACCESS_RULE_SHAPE}, the shape, the line and how to write the rule instead. Nothing is written.
 */
public class McpAccessRuleShapeIT extends WithMcpTestSetup {

    private static final String RULE = "[{DENY view Bob}]";

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > firstViolation( final Map< String, Object > refusal ) {
        final List< Map< String, Object > > violations = ( List< Map< String, Object > > ) refusal.get( "violations" );
        Assertions.assertNotNull( violations, "the refusal must cite violations: " + refusal );
        Assertions.assertFalse( violations.isEmpty(), "the refusal must cite violations: " + refusal );
        return violations.get( 0 );
    }

    /** @param bodyLine the rule's line in the body sent; a frontmatter block the save adds can only push it down */
    private static void assertCitesTheDenyRule( final Map< String, Object > refusal, final int bodyLine ) {
        Assertions.assertEquals( "access rule shape refused", refusal.get( "error" ), refusal.toString() );
        final Map< String, Object > v = firstViolation( refusal );
        Assertions.assertEquals( "ACCESS_RULE_SHAPE", v.get( "code" ), v.toString() );
        Assertions.assertEquals( "DENY", v.get( "shape" ), v.toString() );
        Assertions.assertTrue( ( ( Number ) v.get( "line" ) ).intValue() >= bodyLine, v.toString() );
        Assertions.assertEquals( RULE, v.get( "text" ), v.toString() );
        Assertions.assertTrue( v.get( "message" ).toString().contains( "put it in `code`" ), v.toString() );
    }

    @Test
    public void updatePageRefusesANewDenyRuleCitingTheReasonAndWritesNothing() {
        final String name = uniquePageName( "AccessRuleShapeUpdate" );
        mcp.importPage( name, "Original body." );
        final Map< String, Object > read = mcp.callTool( "read_page", Map.of( "slug", name ) );
        final String hash = ( String ) read.get( "contentHash" );

        final Map< String, Object > refused = mcp.callTool( "update_page", Map.of(
                "slug", name,
                "content", "Original body.\n\n" + RULE + "\n",
                "expectedContentHash", hash ) );

        Assertions.assertEquals( Boolean.FALSE, refused.get( "updated" ), refused.toString() );
        assertCitesTheDenyRule( refused, 3 );
        final Map< String, Object > after = mcp.callTool( "read_page", Map.of( "slug", name ) );
        Assertions.assertEquals( hash, after.get( "contentHash" ), "the refused update wrote nothing: " + after );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void writePagesRefusesAPageWithADenyRuleCitingTheReason() {
        final String name = uniquePageName( "AccessRuleShapeWrite" );

        final Map< String, Object > response = mcp.callTool( "write_pages", Map.of(
                "pages", List.of( Map.of( "slug", name, "content", RULE + "\n\nBody.\n" ) ) ) );

        final List< Map< String, Object > > results = ( List< Map< String, Object > > ) response.get( "results" );
        Assertions.assertNotNull( results, response.toString() );
        final Map< String, Object > entry = results.get( 0 );
        Assertions.assertEquals( Boolean.FALSE, entry.get( "created" ), entry.toString() );
        assertCitesTheDenyRule( entry, 1 );
        Assertions.assertNotEquals( Boolean.TRUE, mcp.callTool( "read_page", Map.of( "slug", name ) ).get( "exists" ),
                "the refused page was not created" );
    }
}
