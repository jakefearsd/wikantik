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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prompt expressed as data: its schema plus a template whose {@code {argName}} placeholders
 * are filled from the caller's arguments (a missing argument takes its declared default).
 * {@code {today}} is always available. A prompt whose text needs computed pieces (an alternate
 * wording, a value that depends on another argument) supplies a {@code derive} function that
 * adds further placeholders from the resolved arguments.
 *
 * <p>Substitution is a single pass: a value containing {@code {x}} or {@code %s} is inserted
 * literally and never re-expanded.</p>
 *
 * @param name        prompt name
 * @param description prompt description (schema)
 * @param args        declared arguments, in schema order
 * @param template    prompt text with {@code {placeholder}}s
 * @param resultDescription description on the returned {@code GetPromptResult}
 * @param derive      extra placeholders computed from the resolved arguments (never null)
 */
public record PromptDef( String name, String description, List< Arg > args, String template,
                         String resultDescription,
                         Function< Map< String, String >, Map< String, String > > derive ) {

    /**
     * One declared prompt argument.
     *
     * @param defaultValue value used when the caller omits the argument
     */
    public record Arg( String name, String description, boolean required, String defaultValue ) {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile( "\\{(\\w+)}" );

    public PromptDef( final String name, final String description, final List< Arg > args,
                      final String template, final String resultDescription ) {
        this( name, description, args, template, resultDescription, resolved -> Map.of() );
    }

    /** The prompt's schema as advertised to MCP clients. */
    public McpSchema.Prompt prompt() {
        return McpSchema.Prompt.builder( name )
                .description( description )
                .arguments( args.stream()
                        .map( a -> McpSchema.PromptArgument.builder( a.name() )
                                .description( a.description() ).required( a.required() ).build() )
                        .toList() )
                .build();
    }

    /** Renders the template for {@code arguments} (may be null) as one USER text message. */
    public McpSchema.GetPromptResult render( final Map< String, Object > arguments ) {
        final Map< String, Object > supplied = arguments != null ? arguments : Map.of();
        final Map< String, String > values = new HashMap<>();
        for ( final Arg a : args ) {
            final Object v = supplied.get( a.name() );
            values.put( a.name(), v != null ? v.toString() : a.defaultValue() );
        }
        values.put( "today", LocalDate.now().toString() );
        values.putAll( derive.apply( values ) );
        final String guide = fill( template, values );
        return McpSchema.GetPromptResult.builder(
                List.of( new McpSchema.PromptMessage(
                        McpSchema.Role.USER,
                        McpSchema.TextContent.builder( guide ).build() ) ) )
                .description( resultDescription )
                .build();
    }

    /** Replaces each {@code {key}} with its value in one pass; unknown keys stay as written. */
    static String fill( final String template, final Map< String, String > values ) {
        final Matcher m = PLACEHOLDER.matcher( template );
        final StringBuilder out = new StringBuilder();
        while ( m.find() ) {
            final String v = values.get( m.group( 1 ) );
            m.appendReplacement( out, Matcher.quoteReplacement( v != null ? v : m.group() ) );
        }
        m.appendTail( out );
        return out.toString();
    }

    /** Adapts this definition to the MCP server's registration type. */
    McpServerFeatures.SyncPromptSpecification toSpecification() {
        return new McpServerFeatures.SyncPromptSpecification( prompt(),
                ( exchange, request ) -> render( request.arguments() ) );
    }
}
