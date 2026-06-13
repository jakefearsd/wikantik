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
package com.wikantik.api.eval;

/** The three deliberately-mixed question kinds in the evaluation corpus (Phase 0). */
public enum BundleCategory {
    /** Plain semantic-similarity question — dense retrieval's home turf. */
    SIMILARITY,
    /** Relational / multi-hop — the fair trial the Knowledge Graph is owed (ADR-0002). */
    RELATIONAL,
    /** Answer straddles a chunk/section boundary — the parent-child trigger evidence. */
    BOUNDARY
}
