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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.Test;

/**
 * The public {@code /sparql} endpoint and the knowledge-MCP {@code sparql_query}
 * tool run unauthenticated user queries from the server's network position, so the
 * two remote-reaching SPARQL capabilities must be closed: {@code SERVICE} federation
 * (SSRF) and {@code java:} function IRIs (classloading). See {@link SparqlQueryGuard}.
 */
class SparqlQueryGuardTest {

    @Test
    void serviceFederationIsRejected() {
        final Query q = QueryFactory.create(
                "SELECT * WHERE { SERVICE <http://169.254.169.254/latest/meta-data/> { ?s ?p ?o } }" );
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
                () -> SparqlQueryGuard.rejectUnsafeConstructs( q ) );
        assertFalse( e.getMessage().isBlank(), "rejection must carry a caller-safe message" );
    }

    @Test
    void serviceInsideAnOptionalIsAlsoRejected() {
        final Query q = QueryFactory.create(
                "SELECT * WHERE { ?s ?p ?o OPTIONAL { SERVICE <http://internal:8080/> { ?a ?b ?c } } }" );
        assertThrows( IllegalArgumentException.class,
                () -> SparqlQueryGuard.rejectUnsafeConstructs( q ),
                "a SERVICE nested in a group must not slip past the walker" );
    }

    @Test
    void javaFunctionIriInFilterIsRejected() {
        final Query q = QueryFactory.create(
                "PREFIX java: <java:> SELECT * WHERE { ?s ?p ?o "
                + "FILTER ( <java:java.lang.System.getProperty>(\"user.home\") = ?o ) }" );
        assertThrows( IllegalArgumentException.class,
                () -> SparqlQueryGuard.rejectUnsafeConstructs( q ),
                "a java: custom-function IRI must be rejected" );
    }

    @Test
    void ordinaryQueryIsAllowed() {
        final Query q = QueryFactory.create(
                "PREFIX wk: <https://wiki.wikantik.com/ns/wikantik#> "
                + "SELECT ?s WHERE { ?s a wk:Person } LIMIT 10" );
        assertDoesNotThrow( () -> SparqlQueryGuard.rejectUnsafeConstructs( q ) );
    }

    @Test
    void ordinaryFilterFunctionIsAllowed() {
        final Query q = QueryFactory.create(
                "SELECT ?s WHERE { ?s ?p ?o FILTER ( STRLEN( STR(?o) ) > 3 ) }" );
        assertDoesNotThrow( () -> SparqlQueryGuard.rejectUnsafeConstructs( q ),
                "standard SPARQL functions must not be mistaken for java: functions" );
    }

    @Test
    void installDisablesServiceFederationGlobally() {
        SparqlQueryGuard.install();
        assertFalse( ARQ.globalServiceAllowed, "SERVICE federation must be globally disabled" );
        // The per-execution context must also carry the flag off.
        assertFalse( SparqlQueryGuard.safeContext().isTrue( ARQ.httpServiceAllowed ),
                "safeContext() must have httpServiceAllowed disabled" );
    }
}
