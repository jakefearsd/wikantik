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

import java.util.Locale;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.Query;
import org.apache.jena.sparql.expr.E_Function;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprFunction;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementBind;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementService;
import org.apache.jena.sparql.syntax.ElementVisitorBase;
import org.apache.jena.sparql.syntax.ElementWalker;
import org.apache.jena.sparql.util.Context;

/**
 * Locks down user-supplied SPARQL executed against the public ontology dataset.
 *
 * <p>Two attacker capabilities have to be removed from the public {@code /sparql}
 * endpoint (and the equivalent knowledge-MCP {@code sparql_query} tool), because
 * they run from the server's network position with no authentication:
 *
 * <ul>
 *   <li><b>{@code SERVICE} federation</b> — a query can name an arbitrary URL for
 *       the engine to fetch, i.e. server-side request forgery into the deployment's
 *       private network (the Docker network, the DB host, the embedder, cloud
 *       metadata). Blocked two ways: {@link ARQ#httpServiceAllowed} is turned off
 *       globally and on every execution's {@link Context}, AND a {@code SERVICE}
 *       clause is rejected syntactically before the query ever runs.</li>
 *   <li><b>{@code java:} function IRIs</b> — a function call whose IRI is
 *       {@code java:some.Class...} makes ARQ resolve the class (running its static
 *       initializer) while looking for a matching function. Rejected syntactically:
 *       any function IRI in the {@code java:} scheme fails the query.</li>
 * </ul>
 *
 * <p>The syntactic rejects are deliberately belt-and-braces on top of the context
 * flag: they hold even if a future Jena upgrade changes how the flag is honoured,
 * and they turn the attack into a clean {@code 400} instead of a best-effort engine
 * refusal buried in an execution exception.
 */
public final class SparqlQueryGuard {

    static {
        // Disable outbound SERVICE federation engine-wide. Both the static toggle
        // and the global context flag are set so no execution can opt back in.
        ARQ.globalServiceAllowed = false;
        ARQ.getContext().setFalse( ARQ.httpServiceAllowed );
    }

    private SparqlQueryGuard() {}

    /** Ensures the static initializer (global SERVICE disable) has run. Call once at bootstrap. */
    public static void install() {
        // no-op; touching the class triggers the static block above.
    }

    /**
     * A per-execution ARQ context with SERVICE federation disabled. Pass this to
     * {@code QueryExecution.create()....context(safeContext())}.
     */
    public static Context safeContext() {
        return ARQ.getContext().copy().setFalse( ARQ.httpServiceAllowed );
    }

    /**
     * Rejects a query that uses a {@code SERVICE} clause or a {@code java:} function
     * IRI. Throws {@link IllegalArgumentException} (mapped to 400 by callers) with a
     * message safe to return to the caller.
     */
    public static void rejectUnsafeConstructs( final Query query ) {
        final Element pattern = query.getQueryPattern();
        if ( pattern == null ) {
            return;
        }
        ElementWalker.walk( pattern, new ElementVisitorBase() {
            @Override
            public void visit( final ElementService el ) {
                throw new IllegalArgumentException(
                        "SPARQL SERVICE (federated query) is not permitted on this endpoint" );
            }

            @Override
            public void visit( final ElementFilter el ) {
                rejectJavaFunction( el.getExpr() );
            }

            @Override
            public void visit( final ElementBind el ) {
                rejectJavaFunction( el.getExpr() );
            }
        } );
    }

    /** Recursively rejects any {@code java:}-scheme function IRI in an expression tree. */
    private static void rejectJavaFunction( final Expr expr ) {
        if ( expr == null ) {
            return;
        }
        if ( expr instanceof ExprFunction fn ) {
            if ( fn instanceof E_Function ef ) {
                final String iri = ef.getFunctionIRI();
                if ( iri != null && iri.toLowerCase( Locale.ROOT ).startsWith( "java:" ) ) {
                    throw new IllegalArgumentException(
                            "SPARQL custom java: functions are not permitted on this endpoint" );
                }
            }
            for ( int i = 1; i <= fn.numArgs(); i++ ) {
                rejectJavaFunction( fn.getArg( i ) );
            }
        }
    }
}
