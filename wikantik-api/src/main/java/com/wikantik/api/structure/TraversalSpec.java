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
package com.wikantik.api.structure;

import java.util.Optional;

/**
 * Parameters for {@code traverse(...)} on the structural index. {@code depthCap}
 * is hard-bounded at 1..5 — beyond that the result set explodes and the agent's
 * token budget will too. {@link #typeFilter} restricts the BFS to a single edge
 * type when set.
 */
public record TraversalSpec(
        RelationDirection direction,
        Optional< RelationType > typeFilter,
        int depthCap
) {
    public TraversalSpec {
        if ( direction == null ) {
            direction = RelationDirection.OUT;
        }
        if ( typeFilter == null ) {
            typeFilter = Optional.empty();
        }
        if ( depthCap < 1 ) {
            depthCap = 1;
        }
        if ( depthCap > 5 ) {
            depthCap = 5;
        }
    }

    public static TraversalSpec outOnce() {
        return new TraversalSpec( RelationDirection.OUT, null, 1 );
    }
}
