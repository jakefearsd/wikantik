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

/**
 * The answer to a push. {@code status}: 200 accepted (or an empty push); 400 {@code rejected} (malformed, length
 * mismatch, lone surrogate, another participant's clientID); 403 {@code not-your-participant} (A6); 409
 * {@code stale-base}, {@code rejoin} (unknown or expired participant) or {@code frozen}; 413 {@code oversize}; 429
 * {@code rate-limited} (A8); 503 {@code store} (the append failed, nothing applied, E35). {@code version} is the head
 * after the call and {@code reason} is null on 200.
 */
public record PushResponse( int status, int version, String reason ) {

    public static PushResponse accepted( final int version ) {
        return new PushResponse( 200, version, null );
    }

    public static PushResponse refused( final int status, final int version, final String reason ) {
        return new PushResponse( status, version, reason );
    }

    public boolean ok() {
        return status == 200;
    }
}
