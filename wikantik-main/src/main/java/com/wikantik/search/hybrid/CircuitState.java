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
package com.wikantik.search.hybrid;

/**
 * Three-state circuit breaker state exposed by {@link QueryEmbedder#circuitState()}.
 *
 * <ul>
 *   <li>{@link #CLOSED} — normal operation. Calls pass through, outcomes recorded in the
 *       rolling window, which can trip the breaker open.</li>
 *   <li>{@link #OPEN} — short-circuiting. Calls return {@code Optional.empty()} without
 *       touching the underlying client, until the cooldown elapses.</li>
 *   <li>{@link #HALF_OPEN} — trial state. A single probe call is allowed through to
 *       decide whether the backend has recovered.</li>
 * </ul>
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
