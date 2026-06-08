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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.wikantik.ontology.OntologyModelManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

class GetOntologyToolTest {

    private OntologyModelManager loadedManager() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        return mgr;
    }

    @Test
    void returnsTBoxClassesPropertiesAndConceptScheme() {
        final var result = new GetOntologyTool( loadedManager() ).execute( Map.of() );
        assertFalse( result.isError() );
        final String json = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        // Entity + content classes from the bundled T-Box:
        assertTrue( json.contains( "Technology" ) && json.contains( "Article" ),
                "T-Box classes present: " + json );
        // Object properties with mappings:
        assertTrue( json.contains( "relatedTo" ) && json.contains( "subPropertyOf" ),
                "object properties + mappings present" );
        // SKOS concept scheme:
        assertTrue( json.contains( "WikiConcepts" ), "concept scheme present" );
        // domain/range surfaced:
        assertTrue( json.contains( "\"domain\"" ) && json.contains( "\"range\"" ),
                "domain/range surfaced" );
    }

    @Test
    void nameIsGetOntology() {
        assertTrue( "get_ontology".equals( new GetOntologyTool( loadedManager() ).name() ) );
    }
}
