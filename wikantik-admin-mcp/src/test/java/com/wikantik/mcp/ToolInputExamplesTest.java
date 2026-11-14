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
package com.wikantik.mcp;

import com.wikantik.TestEngine;
import com.wikantik.mcp.tools.McpTool;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every input example a tool descriptor advertises must pass that tool's own input schema —
 * the validation the SDK applies to {@code tools/call}. A harness that copies a stale example
 * (e.g. {@code pageName} after the rename to {@code slug}) is otherwise rejected on every call.
 */
class ToolInputExamplesTest {

    private TestEngine engine;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        registry = new McpToolRegistry( engine );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private List< McpTool > allTools() {
        final List< McpTool > all = new ArrayList<>( registry.readOnlyTools() );
        all.addAll( registry.authorConfigurableTools() );
        return all;
    }

    @Test
    void everyDeclaredInputExampleValidatesAgainstItsOwnSchema() {
        final JsonSchemaValidator validator = McpJsonDefaults.getSchemaValidator();
        final List< String > failures = new ArrayList<>();
        for ( final McpTool tool : allTools() ) {
            final Map< String, Object > schema = tool.definition().inputSchema();
            final Map< String, Object > composite = new LinkedHashMap<>();
            for ( final Map.Entry< String, Object > e : ToolSchemas.properties( schema ).entrySet() ) {
                if ( !( e.getValue() instanceof Map< ?, ? > p ) || !( p.get( "examples" ) instanceof List< ? > ex )
                        || ex.isEmpty() ) {
                    continue;
                }
                composite.putIfAbsent( e.getKey(), ex.get( 0 ) );
                // each example alone must satisfy the property's own sub-schema
                // wrapped in an object: the validator parses a bare String instance as JSON text
                final JsonSchemaValidator.ValidationResponse r = validator.validate(
                        Map.of( "type", "object", "properties", Map.of( e.getKey(), p ) ),
                        Map.of( e.getKey(), ex.get( 0 ) ) );
                if ( !r.valid() ) {
                    failures.add( tool.name() + "." + e.getKey() + ": " + r.errorMessage() );
                }
            }
            // the assembled call (first example of every property) must satisfy the whole schema
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
    void noInputDocumentationTellsCallersToPassPageName() {
        final List< String > offenders = new ArrayList<>();
        for ( final McpTool tool : allTools() ) {
            for ( final Map.Entry< String, Object > e : ToolSchemas.properties( tool.definition().inputSchema() ).entrySet() ) {
                if ( e.getValue() instanceof Map< ?, ? > p && p.get( "description" ) instanceof String d
                        && ( d.contains( "pageName" ) || d.contains( "pageNames" ) ) ) {
                    offenders.add( tool.name() + "." + e.getKey() );
                }
            }
        }
        assertTrue( offenders.isEmpty(), "input descriptions still mention pageName(s): " + offenders );
    }
}
