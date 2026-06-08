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
    void everyEntityClassIsASubclassOfWkEntity() {
        for ( final String cls : TBoxVocabulary.ENTITY_CLASSES ) {
            assertTrue( subClassOf( cls, TBoxVocabulary.WK + "Entity" ),
                    "wk:" + cls + " must be rdfs:subClassOf wk:Entity" );
        }
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
        assertTrue( subClassOf( "Entity", TBoxVocabulary.SCHEMA + "Thing" ),
                "wk:Entity must be rdfs:subClassOf schema:Thing" );
        assertTrue( subClassOf( "Page", TBoxVocabulary.SCHEMA + "CreativeWork" ),
                "wk:Page must be rdfs:subClassOf schema:CreativeWork" );
    }

    @Test
    void runbookAlignsToSchemaHowTo() {
        assertTrue( subClassOf( "Runbook", TBoxVocabulary.SCHEMA + "HowTo" ),
                "wk:Runbook must align to schema:HowTo" );
    }
}
