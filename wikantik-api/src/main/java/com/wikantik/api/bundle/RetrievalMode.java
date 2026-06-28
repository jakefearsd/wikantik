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
package com.wikantik.api.bundle;

/** Retrieval strategy for a context-bundle request. Wire form is the lowercase name. */
public enum RetrievalMode {
    HYBRID, DENSE, LEXICAL;

    /** Parse a wire value (case-insensitive); null/blank → HYBRID; unknown → IllegalArgumentException. */
    public static RetrievalMode fromWire( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return HYBRID;
        }
        switch ( raw.trim().toLowerCase( java.util.Locale.ROOT ) ) {
            case "hybrid":  return HYBRID;
            case "dense":   return DENSE;
            case "lexical": return LEXICAL;
            default: throw new IllegalArgumentException(
                "invalid retrieval mode '" + raw + "'; valid: hybrid, dense, lexical" );
        }
    }
}
