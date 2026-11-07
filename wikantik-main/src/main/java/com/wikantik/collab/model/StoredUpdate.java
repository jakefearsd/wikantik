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

import com.wikantik.collab.text.ChangeSet;

import java.util.Objects;

/**
 * One accepted update in a session's log. {@code kind} is {@code "text"} (a client edit; {@code authorKey} required) or
 * {@code "server"} (server-originated; {@code authorKey} may be null, as in section 10's author_key column).
 */
public record StoredUpdate( int version, String kind, String clientId, String authorKey, ChangeSet changes ) {
    public StoredUpdate {
        ModelChecks.nonNegative( version, "version" );
        Objects.requireNonNull( kind, "kind" );
        if( !"text".equals( kind ) && !"server".equals( kind ) ) {
            throw new IllegalArgumentException( "kind must be text or server: " + kind );
        }
        Objects.requireNonNull( clientId, "clientId" );
        if( "text".equals( kind ) ) {
            ModelChecks.nonBlank( authorKey, "authorKey" );
        }
        Objects.requireNonNull( changes, "changes" );
    }
}
