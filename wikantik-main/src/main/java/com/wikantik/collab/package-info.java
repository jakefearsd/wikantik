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
/**
 * Collaborative text editing. The session logic in this package depends only on the ports declared here
 * ({@link com.wikantik.collab.CollabPageWriter}, {@link com.wikantik.collab.CollabStore},
 * {@link com.wikantik.collab.CollabPermissions}, {@link com.wikantik.collab.CollabIdentity}) and on injected clocks and
 * executors, never on the engine, JDBC or wall-clock time, so every concurrent and failure case is driven by test
 * doubles. {@code CollabSeamArchTest} enforces it: no zero-argument {@code now()} of {@code Instant},
 * {@code LocalDateTime}, {@code LocalDate}, {@code ZonedDateTime} or {@code OffsetDateTime}, no {@code new Date()},
 * {@code System.currentTimeMillis/nanoTime}, {@code Thread.sleep} or system clocks; no thread or executor creation;
 * no {@code synchronized} methods; no engine or JDBC dependency. Production adapters live in
 * {@code ..collab.adapter..} and are exempt.
 */
package com.wikantik.collab;
