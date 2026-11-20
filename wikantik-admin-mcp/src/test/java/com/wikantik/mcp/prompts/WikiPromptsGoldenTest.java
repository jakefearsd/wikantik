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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Characterisation of every admin MCP prompt: schema and rendered text are pinned against
 * committed goldens under {@code src/test/resources/prompts/}. The only moving part in a
 * rendering is today's date, stored in the goldens as {@code {{TODAY}}}.
 */
class WikiPromptsGoldenTest {

    static final String TODAY_TOKEN = "{{TODAY}}";

    /** Argument values containing characters that formatting engines treat specially. */
    static String awkwardValue( final String argName ) {
        return argName + "%s{topic}$1\\";
    }

    static String schemaOf( final McpSchema.Prompt prompt ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "name=" ).append( prompt.name() ).append( '\n' );
        sb.append( "description=" ).append( prompt.description() ).append( '\n' );
        for ( final McpSchema.PromptArgument a : prompt.arguments() ) {
            sb.append( "arg=" ).append( a.name() ).append( " required=" ).append( a.required() )
              .append( " description=" ).append( a.description() ).append( '\n' );
        }
        return sb.toString();
    }

    static String render( final McpServerFeatures.SyncPromptSpecification spec, final Map< String, Object > args ) {
        final McpSchema.GetPromptResult result = spec.promptHandler().apply( null,
                new McpSchema.GetPromptRequest( spec.prompt().name(), args ) );
        assertEquals( 1, result.messages().size() );
        assertEquals( McpSchema.Role.USER, result.messages().get( 0 ).role() );
        return "description=" + result.description() + "\n---\n"
                + ( (McpSchema.TextContent) result.messages().get( 0 ).content() ).text()
                        .replace( LocalDate.now().toString(), TODAY_TOKEN );
    }

    static Map< String, Object > argsOf( final McpSchema.Prompt prompt, final boolean awkward ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        for ( final McpSchema.PromptArgument a : prompt.arguments() ) {
            args.put( a.name(), awkward ? awkwardValue( a.name() ) : a.name() + "Value" );
        }
        return args;
    }

    static String golden( final String file ) throws IOException {
        try ( InputStream in = WikiPromptsGoldenTest.class.getResourceAsStream( "/prompts/" + file ) ) {
            assertFalse( in == null, "missing golden " + file );
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    @Test
    void schemaMatchesGolden() throws IOException {
        final String actual = WikiPrompts.all().stream().map( s -> schemaOf( s.prompt() ) )
                .collect( Collectors.joining( "\n" ) );
        assertEquals( golden( "schemas.txt" ), actual );
    }

    @Test
    void everyPromptRendersToItsGolden() throws IOException {
        final List< McpServerFeatures.SyncPromptSpecification > all = WikiPrompts.all();
        assertEquals( 8, all.size() );
        for ( final McpServerFeatures.SyncPromptSpecification spec : all ) {
            final String name = spec.prompt().name();
            assertEquals( golden( name + ".none.txt" ), render( spec, Map.of() ), name + " with no arguments" );
            assertEquals( golden( name + ".full.txt" ), render( spec, argsOf( spec.prompt(), false ) ),
                    name + " with every argument set" );
            assertEquals( golden( name + ".awkward.txt" ), render( spec, argsOf( spec.prompt(), true ) ),
                    name + " with awkward argument values" );
        }
    }

    @Test
    void nullArgumentMapRendersLikeAnEmptyOne() {
        for ( final McpServerFeatures.SyncPromptSpecification spec : WikiPrompts.all() ) {
            final McpSchema.GetPromptResult result = spec.promptHandler().apply( null,
                    new McpSchema.GetPromptRequest( spec.prompt().name(), null ) );
            assertEquals( render( spec, Map.of() ),
                    "description=" + result.description() + "\n---\n"
                    + ( (McpSchema.TextContent) result.messages().get( 0 ).content() ).text()
                            .replace( LocalDate.now().toString(), TODAY_TOKEN ) );
        }
    }
}
