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
package com.wikantik.frontmatter.schema;

import com.wikantik.api.frontmatter.schema.FieldSpec;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.pagegraph.ClusterPath;

import java.util.List;
import java.util.Map;

/**
 *  Validates the {@code cluster:} frontmatter field.
 *
 *  <p>Split out of {@link SchemaDrivenFrontmatterValidator} because {@code cluster} is the one
 *  schema field whose rules are not generic: it is scalar-<i>or</i>-list, its legality depends on
 *  another field ({@code type}), and it is the only field that consults the live structural index.
 *  Keeping those rules together also keeps the generic validator generic.</p>
 *
 *  <p>ClusterDeclarationDesign Phase 5.</p>
 */
final class ClusterFieldValidator {

    private ClusterFieldValidator() {
    }

    /**
     *  Validates a {@code cluster} value that may be a list as well as a scalar.
     *
     *  <p>A {@code type: hub} page declares exactly one cluster — a declaration is singular — so
     *  more than one entry there is a blocking {@link Severity#ERROR}. A single-element list on a
     *  hub is just a normalised scalar and raises no error. Everywhere else every membership is
     *  validated independently, so a violation names the offending entry rather than the
     *  stringified list.</p>
     *
     *  @param spec     the {@code cluster} field spec
     *  @param metadata the page's full frontmatter — {@code type} decides whether a list is legal
     *  @param ctx      supplies the declared-cluster predicate
     *  @param out      collects violations
     */
    static void validate( final FieldSpec spec, final Map< String, Object > metadata,
                          final ValidationCtx ctx, final List< FieldViolation > out ) {
        final Object raw = metadata.get( spec.key() );
        if ( raw == null ) {
            return;
        }
        final List< String > memberships = ClusterPath.memberships( raw );
        if ( memberships.isEmpty() ) {
            return;
        }
        if ( isHub( metadata ) && memberships.size() > 1 ) {
            out.add( FieldViolation.of( spec.key(), Severity.ERROR, "cluster.hub.multivalued",
                    "a hub page declares exactly one cluster; '" + spec.key() + "' names "
                            + memberships.size() + ": " + memberships + ". Remove all but one." ) );
            return;
        }
        for ( final String entry : memberships ) {
            validateEntry( spec, entry, ctx, out );
        }
    }

    private static boolean isHub( final Map< String, Object > metadata ) {
        final Object typeRaw = metadata.get( "type" );
        return typeRaw != null && "hub".equalsIgnoreCase( typeRaw.toString().trim() );
    }

    /** The per-entry checks run against each membership. */
    private static void validateEntry( final FieldSpec spec, final String val, final ValidationCtx ctx,
                                       final List< FieldViolation > out ) {
        SlugPatternCheck.check( spec, val, out );
        if ( !ctx.clusterIsDeclared().test( val ) ) {
            // A cluster exists only if a hub page declares it. Advisory by design — naming an
            // undeclared cluster must never block the save (the page stays fully retrievable);
            // it surfaces in the editor and is counted on the /admin/drift burn-down, where the
            // fix is to write the missing hub.
            out.add( FieldViolation.of( spec.key(), Severity.WARNING, "cluster.undeclared",
                    "cluster '" + val + "' is not declared by any hub page. Create a page with"
                            + " 'type: hub' and 'cluster: " + val + "' to declare it." ) );
        }
    }
}
