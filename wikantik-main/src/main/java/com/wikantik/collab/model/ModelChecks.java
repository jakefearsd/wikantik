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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Validation and defensive-copy helpers shared by the value records. */
final class ModelChecks {

    private ModelChecks() {
    }

    static String nonBlank( final String value, final String name ) {
        Objects.requireNonNull( value, name );
        if( value.isBlank() ) {
            throw new IllegalArgumentException( name + " must not be blank" );
        }
        return value;
    }

    static int nonNegative( final int value, final String name ) {
        if( value < 0 ) {
            throw new IllegalArgumentException( name + " must not be negative: " + value );
        }
        return value;
    }

    static long nonNegative( final long value, final String name ) {
        if( value < 0 ) {
            throw new IllegalArgumentException( name + " must not be negative: " + value );
        }
        return value;
    }

    /**
     * Unmodifiable insertion-ordered copy. Deliberately not {@code Map.copyOf}: page meta can hold null values (empty
     * YAML fields), which {@code Map.copyOf} rejects.
     */
    static Map< String, Object > copyMeta( final Map< String, Object > meta, final String name ) {
        Objects.requireNonNull( meta, name );
        return Collections.unmodifiableMap( new LinkedHashMap<>( meta ) );
    }
}
