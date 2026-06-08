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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.junit.jupiter.api.Test;

class ShaclValidationTest {

    private static Shapes loadShapes() {
        final Model shapesModel = ModelFactory.createDefaultModel();
        try ( InputStream in = ShaclValidationTest.class.getResourceAsStream( "/ontology/shapes.ttl" ) ) {
            assertNotNull( in, "/ontology/shapes.ttl must exist on the classpath" );
            RDFDataMgr.read( shapesModel, in, Lang.TURTLE );
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( e );
        }
        return Shapes.parse( shapesModel.getGraph() );
    }

    private static Model data( final String turtle ) {
        final Model m = ModelFactory.createDefaultModel();
        final String prefixes = "@prefix wk: <https://wiki.wikantik.com/ns/wikantik#> .\n"
                + "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n";
        RDFDataMgr.read( m, new java.io.ByteArrayInputStream(
                ( prefixes + turtle ).getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ),
                Lang.TURTLE );
        return m;
    }

    @Test
    void conformingImplementsEdgePasses() {
        final Model good = data(
                "wk:a rdf:type wk:Technology . wk:b rdf:type wk:Concept . wk:a wk:implements wk:b ." );
        final ValidationReport report = ShaclValidator.get().validate( loadShapes(), good.getGraph() );
        assertTrue( report.conforms(), "Technology implements Concept should conform" );
    }

    @Test
    void wrongDomainImplementsEdgeFails() {
        final Model bad = data(
                "wk:a rdf:type wk:Person . wk:b rdf:type wk:Concept . wk:a wk:implements wk:b ." );
        final ValidationReport report = ShaclValidator.get().validate( loadShapes(), bad.getGraph() );
        assertFalse( report.conforms(), "Person (not Technology) implements ... should violate the domain shape" );
    }

    @Test
    void wrongRangeLocatedInEdgeFails() {
        final Model bad = data(
                "wk:a rdf:type wk:Person . wk:b rdf:type wk:Concept . wk:a wk:locatedIn wk:b ." );
        final ValidationReport report = ShaclValidator.get().validate( loadShapes(), bad.getGraph() );
        assertFalse( report.conforms(), "locatedIn a non-Place should violate the range shape" );
    }
}
