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

import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;

class OntologyShaclValidatorTest {

    private Model data( final String turtle ) {
        final Model m = ModelFactory.createDefaultModel();
        final String prefixes = "@prefix wk: <https://wiki.wikantik.com/ns/wikantik#> .\n"
                + "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n";
        RDFDataMgr.read( m, new java.io.ByteArrayInputStream(
                ( prefixes + turtle ).getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ), Lang.TURTLE );
        return m;
    }

    @Test
    void conformingGraphHasNoViolations() {
        final Model good = data(
                "wk:a rdf:type wk:Technology . wk:b rdf:type wk:Concept . wk:a wk:implements wk:b ." );
        assertTrue( new OntologyShaclValidator().validate( good ).isEmpty() );
    }

    @Test
    void violatingGraphReportsStructuredViolation() {
        final Model bad = data(
                "wk:a rdf:type wk:Person . wk:b rdf:type wk:Concept . wk:a wk:implements wk:b ." );
        final List< OntologyShaclValidator.Violation > v = new OntologyShaclValidator().validate( bad );
        assertFalse( v.isEmpty(), "Person implementing... must violate the implements domain shape" );
        assertTrue( v.get( 0 ).focusNode().contains( "#a" ), "violation cites the offending focus node" );
        assertNotNull( v.get( 0 ).message(), "violation carries a message" );
        assertFalse( v.get( 0 ).message().isBlank() );
    }
}
