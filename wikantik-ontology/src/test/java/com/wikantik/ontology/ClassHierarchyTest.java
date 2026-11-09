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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

class ClassHierarchyTest {

    private final Model tbox = TBoxVocabulary.loadTBox();

    private boolean subClassOf( final String childLocal, final String parentIri ) {
        return tbox.contains(
                ResourceFactory.createResource( TBoxVocabulary.WK + childLocal ),
                RDFS.subClassOf,
                ResourceFactory.createResource( parentIri ) );
    }

    @Test
    void entityVocabularyIsGone() {
        for ( final String cls : java.util.List.of( "Entity", "Person", "Organization", "Place", "Event",
                "Product", "Technology", "Concept", "Project", "Version" ) ) {
            assertFalse( tbox.contains( ResourceFactory.createResource( TBoxVocabulary.WK + cls ), null, (org.apache.jena.rdf.model.RDFNode) null ),
                    "wk:" + cls + " belongs to the retired Knowledge Graph and must not be in the T-Box" );
        }
    }

    @Test
    void noKnowledgeGraphRelationshipPredicatesRemain() {
        assertFalse( tbox.listSubjectsWithProperty( org.apache.jena.vocabulary.RDF.type,
                org.apache.jena.vocabulary.OWL.ObjectProperty ).hasNext(),
                "the 21 KG relationship predicates are retired; the T-Box declares no object properties" );
    }

    @Test
    void everyContentClassIsASubclassOfWkPage() {
        for ( final String cls : TBoxVocabulary.CONTENT_CLASSES ) {
            assertTrue( subClassOf( cls, TBoxVocabulary.WK + "Page" ),
                    "wk:" + cls + " must be rdfs:subClassOf wk:Page" );
        }
    }

    @Test
    void rootsAreAnchoredToSchemaOrg() {
        assertTrue( subClassOf( "Page", TBoxVocabulary.SCHEMA + "CreativeWork" ),
                "wk:Page must be rdfs:subClassOf schema:CreativeWork" );
    }

    @Test
    void runbookAlignsToSchemaHowTo() {
        assertTrue( subClassOf( "Runbook", TBoxVocabulary.SCHEMA + "HowTo" ),
                "wk:Runbook must align to schema:HowTo" );
    }
}
