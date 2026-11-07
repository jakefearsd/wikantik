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
 * The answer to a publish. {@code status}: 200 ({@code unchanged} true when there was nothing to save, E21); 400
 * {@code empty} (E1); 403 {@code not-your-participant}; 409 {@code rejoin} or {@code stale-base}; 422
 * {@code writer-refused}; 503 {@code store}. {@code pageVersion} is the session's base page version after the call.
 *
 * <p>Task 3 shape: the naive publish of the Phase 0 stub. Task 4 adds the section 7.3 fields (warnings).</p>
 */
public record PublishResponse( int status, int pageVersion, String reason, boolean unchanged ) {
}
