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
import java.util.Map;

/**
 * The answer to a pull, immediate or from a parked long-poll. {@code status}: 200 with the updates after the requested
 * version ({@code updates} empty on a long-poll timeout); 400 {@code bad-since}; 403 {@code not-your-participant};
 * 409 {@code rejoin}; 410 {@code resync} (the version is older than the ring and the store truncated below it, A10);
 * 503 {@code store}. {@code version} is the head; {@code presence} holds the other participants' last selections (A12),
 * keyed by participant id.
 */
public record PullResponse( int status, List< Update > updates, int version, String reason,
                            Map< String, Selection > presence ) {
    public PullResponse {
        updates = List.copyOf( updates );
        presence = Map.copyOf( presence );
    }

    public static PullResponse refused( final int status, final int version, final String reason ) {
        return new PullResponse( status, List.of(), version, reason, Map.of() );
    }
}
