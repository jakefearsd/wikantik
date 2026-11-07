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
package com.wikantik.collab.seamfixture;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.concurrent.Executors;

/**
 * Deliberate seam-rule violations, one method each, for {@code CollabSeamArchRulesTest}: proves each rule in
 * {@code CollabSeamArchTest} actually detects what it names. Test sources only, so the real rule never sees it.
 */
public final class SeamViolations {

    private SeamViolations() {
    }

    public static Object zonedNow() {
        return ZonedDateTime.now();
    }

    public static Object offsetNow() {
        return OffsetDateTime.now();
    }

    public static Object localDateNow() {
        return LocalDate.now();
    }

    public static Object newDate() {
        return new Date();
    }

    public static Object newThread() {
        return new Thread( () -> { } );
    }

    public static Object virtualThread() {
        return Thread.ofVirtual();
    }

    public static Object executor() {
        return Executors.newSingleThreadExecutor();
    }

    /** Sanctioned: the injected-clock overloads. */
    public static Object injectedClock( final Clock clock ) {
        return Instant.now( clock ).toString() + ZonedDateTime.now( clock ) + LocalDate.now( clock ) + new Date( 0L );
    }
}
