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

import java.sql.Timestamp;
import java.time.Instant;

/** Work-domain value conversions shared by the JDBC work stores (generic nullable column helpers live in {@code NullableColumns}). */
final class SqlValues {

    private SqlValues() {
    }

    static Timestamp timestamp( final Instant at ) {
        return Timestamp.from( at );
    }

    static boolean isClosed( final WorkStatus s ) {
        return s == WorkStatus.DONE || s == WorkStatus.CANCELLED;
    }

    static String autonomyWire( final Autonomy a ) {
        return a == null ? null : a.wire();
    }

    static String doneMeansWire( final DoneMeans d ) {
        return d == null ? null : d.wire();
    }

    static String actorKindWire( final Actor.Kind kind ) {
        return switch ( kind ) {
            case AGENT -> "agent";
            case SYSTEM -> "system";
            default -> "user";
        };
    }

    /** The actor kind a stored {@code actor_kind} column names; anything unknown is a person. */
    static Actor.Kind actorKind( final String wire ) {
        return switch ( wire == null ? "" : wire ) {
            case "agent" -> Actor.Kind.AGENT;
            case "system" -> Actor.Kind.SYSTEM;
            default -> Actor.Kind.HUMAN;
        };
    }

    static String text( final Object o ) {
        return o == null ? null : o.toString();
    }
}
