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

import java.io.InputStream;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

/** Shared constants + loader for the T-Box tests. */
final class TBoxVocabulary {

    private TBoxVocabulary() {}

    static final String WK     = "https://wiki.wikantik.com/ns/wikantik#";
    static final String SCHEMA = "https://schema.org/";

    /** The 9 unified entity classes (UpperCamelCase local names). */
    static final java.util.List< String > ENTITY_CLASSES = java.util.List.of(
            "Person", "Organization", "Place", "Event", "Product",
            "Technology", "Concept", "Project", "Version" );

    /** The 5 content classes (PageType minus UNKNOWN; DESIGN maps to DesignDoc). */
    static final java.util.List< String > CONTENT_CLASSES = java.util.List.of(
            "Hub", "Article", "Reference", "Runbook", "DesignDoc" );

    /** Loads the T-Box from the module classpath into a fresh Jena model. */
    static Model loadTBox() {
        final Model model = ModelFactory.createDefaultModel();
        try ( InputStream in = TBoxVocabulary.class.getResourceAsStream( "/ontology/wikantik.ttl" ) ) {
            if ( in == null ) {
                throw new IllegalStateException( "/ontology/wikantik.ttl not found on classpath" );
            }
            RDFDataMgr.read( model, in, Lang.TURTLE );
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( "failed reading wikantik.ttl", e );
        }
        return model;
    }
}
