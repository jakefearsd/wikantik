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

import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.mcp.tools.McpTool;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Every input example a knowledge-MCP tool descriptor advertises must pass that tool's own
 * input schema (the validation the SDK applies to {@code tools/call}), and no input
 * documentation may tell callers to pass the retired {@code pageName}/{@code pageNames}.
 */
class ToolInputExamplesTest {

    private static List< McpTool > tools() {
        return KnowledgeMcpInitializer.assembleTools( KnowledgeToolDeps.builder()
                .ctxService( mock( ContextRetrievalService.class ) )
                .pageManager( mock( PageManager.class ) )
                .structuralIndex( mock( StructuralIndexService.class ) )
                .forAgent( mock( ForAgentProjectionService.class ) )
                .viewGate( PageViewGate.ALLOW_ALL )
                .build() );
    }

    @Test
    void everyDeclaredInputExampleValidatesAgainstItsOwnSchema() {
        final JsonSchemaValidator validator = McpJsonDefaults.getSchemaValidator();
        final List< String > failures = new ArrayList<>();
        final List< McpTool > tools = tools();
        assertFalse( tools.isEmpty() );
        for ( final McpTool tool : tools ) {
            final Map< String, Object > schema = tool.definition().inputSchema();
            final Map< String, Object > composite = new LinkedHashMap<>();
            for ( final Map.Entry< String, Object > e : ToolSchemas.properties( schema ).entrySet() ) {
                if ( !( e.getValue() instanceof Map< ?, ? > p ) || !( p.get( "examples" ) instanceof List< ? > ex )
                        || ex.isEmpty() ) {
                    continue;
                }
                composite.putIfAbsent( e.getKey(), ex.get( 0 ) );
                // wrapped in an object: the validator parses a bare String instance as JSON text
                final JsonSchemaValidator.ValidationResponse r = validator.validate(
                        Map.of( "type", "object", "properties", Map.of( e.getKey(), p ) ),
                        Map.of( e.getKey(), ex.get( 0 ) ) );
                if ( !r.valid() ) {
                    failures.add( tool.name() + "." + e.getKey() + ": " + r.errorMessage() );
                }
            }
            if ( !composite.keySet().containsAll( ToolSchemas.required( schema ) ) ) {
                continue; // a required arg carries no example; nothing to assemble
            }
            final JsonSchemaValidator.ValidationResponse r = validator.validate( schema, composite );
            if ( !r.valid() ) {
                failures.add( tool.name() + " (assembled call): " + r.errorMessage() );
            }
        }
        assertTrue( failures.isEmpty(), "input examples rejected by their own schema: " + failures );
    }

    @Test
    void noInputDocumentationMentionsPageName() {
        final List< String > offenders = new ArrayList<>();
        for ( final McpTool tool : tools() ) {
            for ( final Map.Entry< String, Object > e : ToolSchemas.properties( tool.definition().inputSchema() ).entrySet() ) {
                if ( e.getValue() instanceof Map< ?, ? > p && p.get( "description" ) instanceof String d
                        && d.contains( "pageName" ) ) {
                    offenders.add( tool.name() + "." + e.getKey() );
                }
            }
        }
        assertTrue( offenders.isEmpty(), "input descriptions still mention pageName(s): " + offenders );
    }
}
