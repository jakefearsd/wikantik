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
package com.wikantik.pagegraph.spine;

import java.util.List;

/**
 *  What renaming a cluster would change, computed before anything is written.
 *
 *  <p>A rename carries the whole subtree: renaming {@code machine-learning} also moves
 *  {@code machine-learning/mlops}. That is the cost the path-in-the-value form implies, and
 *  the reason this operation exists at all — doing it by hand is O(member pages).</p>
 *
 *  @param from     the cluster being renamed
 *  @param to       its new path
 *  @param changes  one entry per page whose {@code cluster:} would be rewritten
 *  @param conflicts human-readable reasons the rename must not proceed; empty means safe
 */
public record ClusterRenamePlan(
        String from,
        String to,
        List< PageChange > changes,
        List< String > conflicts ) {

    /** One page's cluster rewrite. */
    public record PageChange( String slug, String fromCluster, String toCluster ) {}

    public ClusterRenamePlan {
        changes   = changes   == null ? List.of() : List.copyOf( changes );
        conflicts = conflicts == null ? List.of() : List.copyOf( conflicts );
    }

    /** Whether applying this plan would leave the taxonomy invalid. */
    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    /** Whether the rename would touch nothing (the cluster has no member pages). */
    public boolean isEmpty() {
        return changes.isEmpty();
    }
}
