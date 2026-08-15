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
package com.wikantik.api.pagegraph;

/**
 *  Segment-aware operations on a cluster path such as {@code machine-learning/mlops}.
 *
 *  <p>Every consumer that compares cluster paths <b>must</b> go through this class.
 *  A bare {@code String.startsWith} is wrong: it reports {@code machine-learning-ops}
 *  as a descendant of {@code machine-learning}, silently merging two unrelated
 *  clusters. The methods here only ever treat {@code '/'} as a boundary.</p>
 *
 *  <p>These operations are <b>depth-agnostic on purpose</b>. The taxonomy is limited
 *  to one level of nesting, but that limit lives solely in
 *  {@code FrontmatterSchema.CLUSTER_SLUG_PATTERN} — nothing here assumes it, so the
 *  limit stays a validation policy rather than an architectural constraint.</p>
 *
 *  <p>See the {@code ClusterDeclarationDesign} wiki page, "Implementation invariant".</p>
 */
public final class ClusterPath {

    /** The one character that separates cluster path segments. */
    public static final char SEPARATOR = '/';

    private ClusterPath() {
    }

    /**
     *  Returns {@code true} when {@code candidate} is {@code ancestor} itself or sits
     *  beneath it in the taxonomy.
     *
     *  <p>{@code machine-learning/mlops} is a descendant of {@code machine-learning};
     *  {@code machine-learning-ops} is <b>not</b>.</p>
     *
     *  @param candidate the cluster path being tested; {@code null} matches nothing
     *  @param ancestor  the cluster path to test against; {@code null} matches nothing
     *  @return whether candidate is ancestor or lies below it
     */
    public static boolean isSelfOrDescendant( final String candidate, final String ancestor ) {
        if ( candidate == null || ancestor == null ) {
            return false;
        }
        if ( candidate.equals( ancestor ) ) {
            return true;
        }
        // The character immediately after the ancestor must be the separator, or this
        // is a sibling that merely shares a string prefix.
        return candidate.length() > ancestor.length()
                && candidate.charAt( ancestor.length() ) == SEPARATOR
                && candidate.startsWith( ancestor );
    }

    /**
     *  Returns the immediate parent of a cluster path, or {@code null} for a top-level
     *  cluster (and for {@code null} input).
     *
     *  @param path the cluster path
     *  @return the parent path, or {@code null} when there is none
     */
    public static String parent( final String path ) {
        if ( path == null ) {
            return null;
        }
        final int slash = path.lastIndexOf( SEPARATOR );
        return slash <= 0 ? null : path.substring( 0, slash );
    }
}
