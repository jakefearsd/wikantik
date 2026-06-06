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
package com.wikantik.mcp.prompts;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WikiPrompts}. The prompt specifications are pure template
 * functions (no managers), so we invoke each prompt's handler directly with a
 * superset of arguments and assert it renders a non-empty USER message.
 */
class WikiPromptsTest {

    /** A superset of every argument any prompt reads; each handler picks the keys it needs. */
    private static final Map< String, Object > ARGS = new HashMap<>( Map.of(
            "topic", "QuantumComputing",
            "type", "reference",
            "pageName", "Main",
            "oldName", "OldName",
            "newName", "NewName",
            "clusterName", "Security",
            "hub", "SecurityHub" ) );

    private static String firstText( final McpSchema.GetPromptResult result ) {
        final McpSchema.PromptMessage msg = result.messages().get( 0 );
        return ( (McpSchema.TextContent) msg.content() ).text();
    }

    @Test
    void exposesTheExpectedPromptSet() {
        final Set< String > names = WikiPrompts.all().stream()
                .map( s -> s.prompt().name() )
                .collect( Collectors.toSet() );
        assertEquals( 8, names.size(), names.toString() );
        assertTrue( names.containsAll( List.of(
                "create-article", "summarize-topic", "audit-links", "rename-page" ) ), names.toString() );
    }

    @Test
    void everyPromptHandlerRendersANonEmptyUserMessage() {
        for ( final McpServerFeatures.SyncPromptSpecification spec : WikiPrompts.all() ) {
            final String name = spec.prompt().name();
            final McpSchema.GetPromptResult result =
                    spec.promptHandler().apply( null, new McpSchema.GetPromptRequest( name, ARGS ) );

            assertFalse( result.messages().isEmpty(), name + ": no messages" );
            assertEquals( McpSchema.Role.USER, result.messages().get( 0 ).role(), name );
            assertFalse( firstText( result ).isBlank(), name + ": blank message" );
        }
    }

    @Test
    void createArticleInterpolatesTheTopicAndType() {
        final McpServerFeatures.SyncPromptSpecification spec = WikiPrompts.all().stream()
                .filter( s -> s.prompt().name().equals( "create-article" ) )
                .findFirst().orElseThrow();
        final String text = firstText(
                spec.promptHandler().apply( null, new McpSchema.GetPromptRequest( "create-article", ARGS ) ) );
        assertTrue( text.contains( "QuantumComputing" ), text );
        assertTrue( text.contains( "type: reference" ), text );
    }

    @Test
    void handlersTolerateMissingArguments() {
        // Every handler uses getOrDefault, so an empty argument map must still render.
        for ( final McpServerFeatures.SyncPromptSpecification spec : WikiPrompts.all() ) {
            final McpSchema.GetPromptResult result = spec.promptHandler().apply(
                    null, new McpSchema.GetPromptRequest( spec.prompt().name(), Map.of() ) );
            assertFalse( firstText( result ).isBlank(), spec.prompt().name() );
        }
    }
}
