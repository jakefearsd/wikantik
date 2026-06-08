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
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NodeTypeMappingTest {

    @Test
    void mapsEachEntityType() {
        assertEquals( "Person",       NodeTypeMapping.classLocalName( "person" ) );
        assertEquals( "Organization", NodeTypeMapping.classLocalName( "organization" ) );
        assertEquals( "Technology",   NodeTypeMapping.classLocalName( "technology" ) );
        assertEquals( "Version",      NodeTypeMapping.classLocalName( "version" ) );
    }

    @Test
    void mapsPageTypesIncludingDesignAliases() {
        assertEquals( "Article",   NodeTypeMapping.classLocalName( "article" ) );
        assertEquals( "Hub",       NodeTypeMapping.classLocalName( "hub" ) );
        assertEquals( "DesignDoc", NodeTypeMapping.classLocalName( "design" ) );
        assertEquals( "DesignDoc", NodeTypeMapping.classLocalName( "design_doc" ) );
    }

    @Test
    void isCaseInsensitive() {
        assertEquals( "Product", NodeTypeMapping.classLocalName( "Product" ) );
    }

    @Test
    void nullOrUnknownDefaultsToConcept() {
        assertEquals( "Concept", NodeTypeMapping.classLocalName( null ) );
        assertEquals( "Concept", NodeTypeMapping.classLocalName( "" ) );
        assertEquals( "Concept", NodeTypeMapping.classLocalName( "intelligence-summary" ) );
    }

    // -------- schemaOrgType (Phase 6: SEO @type re-sourced from the ontology mapping) --------

    @Test
    void schemaOrgTypeMapsPageTypesToSchemaTypes() {
        assertEquals( "CollectionPage", NodeTypeMapping.schemaOrgType( "hub" ) );
        assertEquals( "Article",        NodeTypeMapping.schemaOrgType( "article" ) );
        assertEquals( "HowTo",          NodeTypeMapping.schemaOrgType( "runbook" ) );
        assertEquals( "TechArticle",    NodeTypeMapping.schemaOrgType( "design" ) );
        assertEquals( "TechArticle",    NodeTypeMapping.schemaOrgType( "design_doc" ) );
    }

    @Test
    void schemaOrgTypeDefaultsToArticle() {
        assertEquals( "Article", NodeTypeMapping.schemaOrgType( "reference" ) );
        assertEquals( "Article", NodeTypeMapping.schemaOrgType( null ) );
        assertEquals( "Article", NodeTypeMapping.schemaOrgType( "" ) );
        assertEquals( "Article", NodeTypeMapping.schemaOrgType( "totally-unknown" ) );
    }

    @Test
    void schemaOrgTypeIsCaseInsensitive() {
        assertEquals( "HowTo", NodeTypeMapping.schemaOrgType( "Runbook" ) );
    }
}
