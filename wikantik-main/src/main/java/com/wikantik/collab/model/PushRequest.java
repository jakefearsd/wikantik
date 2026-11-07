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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code POST /updates} (section 6.2 as amended): the updates sent from {@code version}, and optionally a selection (A12). */
public record PushRequest( UUID sessionId, String participantId, int version, List< Update > updates,
                           Selection selection ) {
    public PushRequest {
        Objects.requireNonNull( sessionId, "sessionId" );
        ModelChecks.nonBlank( participantId, "participantId" );
        ModelChecks.nonNegative( version, "version" );
        updates = List.copyOf( updates );
    }
}
