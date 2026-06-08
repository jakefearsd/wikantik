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

import java.nio.file.Path;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Tdb2SmokeTest {

    @Test
    void canOpenTdb2DatasetAndWriteInATransaction( @TempDir final Path dir ) {
        final Dataset ds = TDB2Factory.connectDataset( dir.toString() );
        try {
            ds.begin( ReadWrite.WRITE );
            final Model g = ds.getNamedModel( "urn:test:g1" );
            g.add( ResourceFactory.createResource( "urn:test:s" ),
                   RDF.type,
                   ResourceFactory.createResource( "urn:test:T" ) );
            ds.commit();
        } finally {
            ds.end();
        }
        ds.begin( ReadWrite.READ );
        try {
            assertTrue( ds.getNamedModel( "urn:test:g1" ).size() == 1, "one triple persisted in TDB2" );
        } finally {
            ds.end();
            ds.close();
        }
    }
}
