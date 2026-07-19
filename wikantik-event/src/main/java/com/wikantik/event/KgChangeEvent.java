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
package com.wikantik.event;

import java.util.Set;
import java.util.UUID;

/**
 * Fired after a durable write to the Knowledge Graph ({@code kg_nodes}/{@code kg_edges}).
 *
 * <p>The payload is deliberately dumb — entity ids only, no KG domain types — so this
 * module stays dependency-free and consumers always re-read current database state
 * rather than trusting an event-time snapshot. {@code touchedEntityIds} are entities
 * whose ontology named graphs should be re-projected; {@code removedEntityIds} are
 * entities whose graphs should be dropped. Emitted by the two KG write funnels
 * ({@code DefaultKnowledgeGraphService}, {@code KgMaterializationService}) with the
 * emitting service instance as the {@code WikiEventManager} client object. See
 * {@code docs/superpowers/specs/2026-07-19-kg-change-events-design.md}.</p>
 */
public final class KgChangeEvent extends WikiEvent {

    /** KG content changed. Page events use 10&ndash;28, security events 30&ndash;54. */
    public static final int KG_CHANGED = 60;

    private static final long serialVersionUID = 1L;

    private final Set< UUID > touchedEntityIds;
    private final Set< UUID > removedEntityIds;

    public KgChangeEvent( final Object src, final Set< UUID > touchedEntityIds,
                          final Set< UUID > removedEntityIds ) {
        super( src, KG_CHANGED );
        this.touchedEntityIds = touchedEntityIds == null ? Set.of() : Set.copyOf( touchedEntityIds );
        this.removedEntityIds = removedEntityIds == null ? Set.of() : Set.copyOf( removedEntityIds );
    }

    /** Entity ids whose named graphs should be re-projected from current DB state. */
    public Set< UUID > touchedEntityIds() {
        return touchedEntityIds;
    }

    /** Entity ids whose named graphs should be removed from the ontology dataset. */
    public Set< UUID > removedEntityIds() {
        return removedEntityIds;
    }
}
