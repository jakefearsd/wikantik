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
package com.wikantik.knowledge.bundle;

/**
 * Structure-conditional query-decomposition wiring (default off): the {@link QueryPlanner} that
 * splits a query into sub-queries, the {@link SubQueryFusion} strategy that merges their
 * per-query candidates, and whether the feature is active at all.
 */
record QueryDecomposition( QueryPlanner planner, SubQueryFusion fusion, boolean enabled ) {

    /** The pre-decomposition behaviour: single-pass, byte-identical to before the feature existed. */
    static QueryDecomposition disabled() {
        return new QueryDecomposition( new PassthroughQueryPlanner(), new SubQueryFusion( 60 ), false );
    }
}
