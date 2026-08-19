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
package com.wikantik.ontology.runtime;

/**
 * Immutable snapshot of the ontology TDB2 compactor's state.
 *
 * <p>Kept as a SEPARATE record from {@link OntologyRebuildStatus} rather than
 * growing that record's fields: {@code OntologyRebuildStatus}'s 4-arg canonical
 * constructor is called positionally from wikantik-rest
 * ({@code AdminOntologyResourceTest}), outside this module's ownership —
 * widening its arity would break that call site. This record is purely
 * additive and has no existing contract to preserve.
 *
 * @param state                    mirrors {@link OntologyRebuildCoordinator#status()}'s shared
 *                                 busy/idle state string ("IDLE" | "STARTING" | "RUNNING" | "COMPACTING") —
 *                                 rebuild and compaction share one busy guard, so a caller can tell a
 *                                 compaction specifically is in flight by checking for "COMPACTING".
 * @param lastCompactionEpochMillis wall-clock time the last compaction finished, or -1 if never run
 * @param lastSizeBeforeBytes      on-disk TDB2 store size immediately before the last compaction, or -1 if never run
 * @param lastSizeAfterBytes       on-disk TDB2 store size immediately after the last compaction, or -1 if never run
 * @param lastError                null when healthy; the last compaction failure's message otherwise
 */
public record OntologyCompactionStatus(
        String state,
        long lastCompactionEpochMillis,
        long lastSizeBeforeBytes,
        long lastSizeAfterBytes,
        String lastError
) {}
