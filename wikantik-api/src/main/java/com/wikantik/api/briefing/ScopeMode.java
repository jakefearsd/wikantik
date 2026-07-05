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
package com.wikantik.api.briefing;

import java.util.Locale;

/** Whether a briefing may widen beyond the requested pins/clusters (PREFER) or must stay within
 *  them (STRICT). */
public enum ScopeMode {
    PREFER, STRICT;

    /** null/blank → PREFER; otherwise case-insensitive name; unknown → IllegalArgumentException. */
    public static ScopeMode fromWire( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return PREFER;
        }
        return switch ( raw.trim().toLowerCase( Locale.ROOT ) ) {
            case "prefer" -> PREFER;
            case "strict" -> STRICT;
            default -> throw new IllegalArgumentException( "Unknown scope_mode: " + raw );
        };
    }
}
