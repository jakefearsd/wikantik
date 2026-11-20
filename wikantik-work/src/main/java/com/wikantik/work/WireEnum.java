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

/** An enum stored and sent as a lowercase wire string; one place for the parse every such enum needs. */
interface WireEnum {

    /** The lowercase value as stored in the database. */
    String wire();

    /** The constant whose wire value is {@code value}, or null when none is (or {@code value} is not a string). */
    static < E extends Enum< E > & WireEnum > E tryParse( final Class< E > type, final Object value ) {
        for ( final E candidate : type.getEnumConstants() ) {
            if ( candidate.wire().equals( value ) ) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Like {@link #tryParse} but throws {@link WorkValidationException} on field {@code label}, "Unknown
     * {label}: {value}", when nothing matches.
     */
    static < E extends Enum< E > & WireEnum > E parse( final Class< E > type, final String value,
                                                       final String label ) {
        final E found = tryParse( type, value );
        if ( found == null ) {
            throw new WorkValidationException( label, "Unknown " + label + ": " + value );
        }
        return found;
    }
}
