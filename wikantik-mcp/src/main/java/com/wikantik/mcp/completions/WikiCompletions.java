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
package com.wikantik.mcp.completions;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import com.wikantik.api.managers.ReferenceManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides MCP completion providers for wiki prompt arguments, enabling
 * page name autocompletion when agents interact with prompts.
 */
public final class WikiCompletions {

    private static final int MAX_COMPLETIONS = 20;

    private WikiCompletions() {
    }

    /**
     * Creates completion specifications for all prompts that accept page name arguments.
     */
    public static List< McpServerFeatures.SyncCompletionSpecification > all( final ReferenceManager referenceManager ) {
        return List.of(
                pageNameCompletion( "audit-links", "pageName", referenceManager ),
                pageNameCompletion( "rename-page", "oldName", referenceManager ),
                pageNameCompletion( "rename-page", "newName", referenceManager )
        );
    }

    private static McpServerFeatures.SyncCompletionSpecification pageNameCompletion(
            final String promptName,
            final String argumentName,
            final ReferenceManager referenceManager ) {

        final McpSchema.PromptReference ref = new McpSchema.PromptReference( promptName );

        return new McpServerFeatures.SyncCompletionSpecification( ref, ( exchange, request ) -> {
            final McpSchema.CompleteRequest.CompleteArgument argument = request.argument();

            // Only provide completions for the expected argument name
            if ( !argumentName.equals( argument.name() ) ) {
                return new McpSchema.CompleteResult(
                        new McpSchema.CompleteResult.CompleteCompletion( List.of(), 0, false ) );
            }

            final String prefix = argument.value() != null ? argument.value() : "";
            final Collection< String > allPages = referenceManager.findCreated();
            final List< String > matches = allPages.stream()
                    .filter( name -> name.toLowerCase().startsWith( prefix.toLowerCase() ) )
                    .sorted()
                    .limit( MAX_COMPLETIONS )
                    .collect( Collectors.toList() );

            final long totalMatches = allPages.stream()
                    .filter( name -> name.toLowerCase().startsWith( prefix.toLowerCase() ) )
                    .count();

            return new McpSchema.CompleteResult(
                    new McpSchema.CompleteResult.CompleteCompletion(
                            matches,
                            ( int ) totalMatches,
                            totalMatches > MAX_COMPLETIONS ) );
        } );
    }
}
