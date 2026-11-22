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
package com.wikantik.work;

/** Observer of service outcomes (metrics); every method is a no-op by default. */
public interface WorkListener {
    default void denied( final Action a ) {
    }

    default void claim( final ClaimOutcome o ) {
    }

    /** A deployment reached {@code state} (its wire value), a request included. */
    default void deployment( final String state ) {
    }

    /** A notification left through {@code channel} (inbox, email, webhook) with {@code outcome} (delivered, dropped, failed). */
    default void notified( final String channel, final String outcome ) {
    }

    /** A code-host event of {@code kind} (the lower-case event kind) ended as {@code outcome} (applied, duplicate, ignored). */
    default void codeHost( final String kind, final String outcome ) {
    }

    /** An inbound code-host delivery was refused for {@code reason}. */
    default void codeHostRejected( final HookRejection reason ) {
    }

    /** An agent met a gate ({@code approve}, {@code close}, {@code review}, {@code merge}, {@code direct}). */
    default void gate( final String gate, final boolean allowed ) {
    }

    /** The dispatcher skipped an event it could not turn into notifications (it is recorded in work_notify_skipped). */
    default void notifySkipped() {
    }
}
