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

import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.ontology.OntologyModelManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Locks the #2 routing guidance into the tool descriptions so it can't silently drift. */
class ToolDescriptionRoutingTest {

    @Test
    void assembleBundleMentionsCoverageEscalation() {
        final String d = new AssembleBundleTool( q -> null, () -> null ).definition().description();
        assertTrue( d.contains( "coverage" ), "assemble_bundle should mention the coverage block" );
        assertTrue( d.toLowerCase().contains( "sparql_query" ) || d.toLowerCase().contains( "get_ontology" ),
                "assemble_bundle should name the escalation tools" );
    }

    @Test
    void sparqlMentionsCountsAndEnumerations() {
        final String d = new SparqlQueryTool( OntologyModelManager.inMemory() ).definition().description();
        assertTrue( d.toLowerCase().contains( "count" ) || d.toLowerCase().contains( "enumerat" ),
                "sparql_query should advertise exact counts/enumerations" );
    }

    @Test
    void getOntologyMentionsCountsAndLists() {
        final String d = new GetOntologyTool( OntologyModelManager.inMemory() ).definition().description();
        assertTrue( d.toLowerCase().contains( "count" ) || d.toLowerCase().contains( "list" ),
                "get_ontology should advertise authoritative counts/lists" );
    }

    @Test
    void discoverSchemaMentionsEnumerateNodeTypes() {
        final String d = new DiscoverSchemaTool( mock( KnowledgeGraphService.class ) ).definition().description();
        assertTrue( d.toLowerCase().contains( "enumerat" ) || d.toLowerCase().contains( "count" ),
                "discover_schema should mention enumeration of node types/counts" );
    }
}
