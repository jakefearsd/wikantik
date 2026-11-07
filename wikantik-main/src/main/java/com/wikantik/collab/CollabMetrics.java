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

/**
 * Port counting collaborative editing's observations for Prometheus (docs/MultiEdit.MD section 12.3). Telemetry only:
 * nothing reads these back. Every method defaults to doing nothing; the core calls them through a guard
 * ({@link GuardedMetrics}), so a failing meter never fails an edit.
 */
public interface CollabMetrics {

    /** Push result: applied (200). */
    String ACCEPTED = "accepted";
    /** Push result: 409 {@code stale-base}; the client rebases. */
    String REBASE = "rebase";
    /** Push result: any other refusal. */
    String REJECTED = "rejected";
    /** Publish result: saved as a new page version. */
    String PUBLISHED = "published";
    /** Publish result: 200 with nothing to save (E21). */
    String UNCHANGED = "unchanged";
    /** Publish result: 409 (an outside write was merged, not synced, frozen). */
    String CONFLICT = "conflict";
    /** Publish result: any other 4xx (identity, empty, oversize, writer-refused, in progress, no-create). */
    String REFUSED = "refused";
    /** Publish result: 500 {@code writer-failed}, or a publish that threw. */
    String FAILED = "failed";
    /** Publish result: 503 {@code store}. */
    String STORE = "store";

    /** Counts nothing. */
    CollabMetrics NONE = new CollabMetrics() {
    };

    /** One push answered; {@code result} is {@link #ACCEPTED}, {@link #REBASE} or {@link #REJECTED}. */
    default void push( final String result ) {
    }

    /** How long one push took, in nanoseconds (by the injected clock). */
    default void pushLatency( final long nanos ) {
    }

    /** One publish answered; {@code result} is one of the publish results above. */
    default void publish( final String result ) {
    }

    /** One outside write merged into a draft (an {@code external-change} event). */
    default void externalMerge() {
    }

    /** One pull answered 410 {@code resync}. */
    default void resync() {
    }
}
