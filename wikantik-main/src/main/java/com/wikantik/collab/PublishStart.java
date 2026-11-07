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
package com.wikantik.collab;

import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.PublishResponse;

import java.util.Map;
import java.util.Objects;

/**
 * The first half of a publish (section 7.3), from {@link CollabSession#startPublish}: either {@code response} (the
 * publish was answered before any save) or {@code ticket} (the pending-publish marker is stored; the caller saves
 * {@code ticket.request()} outside every session lock and hands the outcome to {@link CollabSession#finishPublish} or
 * the failure to {@link CollabSession#failPublish}). Exactly one of the two is set.
 */
public record PublishStart( PublishResponse response, Ticket ticket ) {

    public PublishStart {
        if( ( response == null ) == ( ticket == null ) ) {
            throw new IllegalArgumentException( "exactly one of response and ticket is set" );
        }
    }

    /**
     * A publish inside its save: the snapshot it saves ({@code version} V, {@code doc} S_V, {@code meta} M_V, normalised,
     * at meta seq {@code metaSeq}) on base page version {@code baseVersion} B, by {@code authorKey} (shown as
     * {@code displayName}), and the save request (whose meta keeps the base's original typed value for every field the
     * draft left unchanged). V and {@code metaSeq} bound the contributors the publish credits (ruling R2).
     */
    public record Ticket( String participantId, String authorKey, String displayName, int version, String doc,
                          Map< String, Object > meta, long metaSeq, int baseVersion, PublishRequest request ) {
        public Ticket {
            Objects.requireNonNull( authorKey, "authorKey" );
            Objects.requireNonNull( doc, "doc" );
            meta = java.util.Collections.unmodifiableMap( new java.util.LinkedHashMap<>( meta ) );
            Objects.requireNonNull( request, "request" );
        }
    }
}
