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
package com.wikantik.ontology.projection;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

class PageProjectorTest {

    private static final String CID = "01KTGSV4B1PR4RBEGQGBJVNXN0";

    private PageRecord page( final String type, final String cluster, final List< String > tags ) {
        return new PageRecord( CID, "GraphDatabaseFundamentals", "Graph Database Fundamentals",
                type, cluster, tags, "Intro to graph DBs", "2026-03-14", "claude-code-researcher" );
    }

    @Test
    void typesPageByContentClassAndSetsUrlAndTitle() {
        final Model g = PageProjector.project( page( "article", "graph-databases", List.of() ) );
        final var subj = ResourceFactory.createResource( Iris.page( CID ) );
        assertTrue( g.contains( subj, RDF.type, ResourceFactory.createResource( Iris.term( "Article" ) ) ) );
        assertTrue( g.contains( subj,
                ResourceFactory.createProperty( "https://schema.org/url" ),
                ResourceFactory.createResource( "https://wiki.wikantik.com/wiki/GraphDatabaseFundamentals" ) ) );
        assertTrue( g.contains( subj,
                ResourceFactory.createProperty( "http://purl.org/dc/terms/title" ),
                g.createLiteral( "Graph Database Fundamentals" ) ) );
    }

    @Test
    void hubMapsToHubClass() {
        final Model g = PageProjector.project( page( "hub", "graph-databases", List.of() ) );
        assertTrue( g.contains( ResourceFactory.createResource( Iris.page( CID ) ),
                RDF.type, ResourceFactory.createResource( Iris.term( "Hub" ) ) ) );
    }

    @Test
    void linksTagsAndClusterAsDctSubject() {
        final Model g = PageProjector.project(
                page( "article", "graph-databases", List.of( "databases", "nosql" ) ) );
        final var subj = ResourceFactory.createResource( Iris.page( CID ) );
        final var dctSubject = ResourceFactory.createProperty( "http://purl.org/dc/terms/subject" );
        assertTrue( g.contains( subj, dctSubject, ResourceFactory.createResource( Iris.concept( "databases" ) ) ) );
        assertTrue( g.contains( subj, dctSubject, ResourceFactory.createResource( Iris.concept( "nosql" ) ) ) );
        assertTrue( g.contains( subj, dctSubject, ResourceFactory.createResource( Iris.concept( "graph-databases" ) ) ),
                "the cluster is also a dct:subject concept" );
    }
}
