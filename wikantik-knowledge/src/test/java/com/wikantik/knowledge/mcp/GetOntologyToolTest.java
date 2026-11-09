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
    void returnsPageClassesAndConceptSchemeOnly() {
        final var result = new GetOntologyTool( loadedManager() ).execute( Map.of() );
        assertFalse( result.isError() );
        final String json = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        // Content classes from the bundled T-Box; the retired entity vocabulary is gone:
        assertTrue( json.contains( "Article" ) && json.contains( "Runbook" ),
                "T-Box classes present: " + json );
        assertFalse( json.contains( "Technology" ) || json.contains( "relatedTo" ),
                "entity classes and KG predicates must not be advertised: " + json );
        assertTrue( json.replaceAll( "\\s", "" ).contains( "\"objectProperties\":[]" ), "no object properties remain: " + json );
        // SKOS concept scheme:
        assertTrue( json.contains( "WikiConcepts" ), "concept scheme present" );
    }

    @Test
    void nameIsGetOntology() {
        assertTrue( "get_ontology".equals( new GetOntologyTool( loadedManager() ).name() ) );
    }
}
