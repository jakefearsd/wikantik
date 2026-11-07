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
package com.wikantik.collab.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The answer to a join (section 6.2): the participant's identity, the head document and version the client starts
 * from, the frontmatter, the base page version and who else is here. {@code lastEditAt} is null until the first edit.
 */
public record JoinResponse( UUID sessionId, String participantId, String clientId, int version, String doc,
                            long metaSeq, Map< String, Object > metadata, int basePageVersion, List< Peer > participants,
                            boolean unpublished, Instant lastEditAt ) {

    /** One participant as other participants see it. */
    public record Peer( String participantId, String displayName, String color ) {
    }

    public JoinResponse {
        Objects.requireNonNull( sessionId, "sessionId" );
        ModelChecks.nonBlank( participantId, "participantId" );
        ModelChecks.nonBlank( clientId, "clientId" );
        ModelChecks.nonNegative( version, "version" );
        Objects.requireNonNull( doc, "doc" );
        metadata = ModelChecks.copyMeta( metadata, "metadata" );
        participants = List.copyOf( participants );
    }
}
