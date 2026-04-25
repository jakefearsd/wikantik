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

/**
 * An authored, directional relationship between two pages identified by
 * their {@code canonical_id}s. Use {@link RelationEdge} when you need the
 * resolved page slugs in the same payload.
 */
public record Relation(
        String sourceId,
        String targetId,
        RelationType type
) {
    public Relation {
        if ( sourceId == null || sourceId.isBlank() ) {
            throw new IllegalArgumentException( "sourceId required" );
        }
        if ( targetId == null || targetId.isBlank() ) {
            throw new IllegalArgumentException( "targetId required" );
        }
        if ( type == null ) {
            throw new IllegalArgumentException( "type required" );
        }
        if ( sourceId.equals( targetId ) ) {
            throw new IllegalArgumentException( "self-referential relation rejected" );
        }
    }
}
