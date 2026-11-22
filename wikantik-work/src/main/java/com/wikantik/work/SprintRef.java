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

import java.util.regex.Pattern;

/** A sprint named in a filter: one by id, the project's active one, or none (items in no sprint). */
public record SprintRef( Kind kind, Long sprintId ) {

    /** What a reference names. */
    public enum Kind { ID, ACTIVE, NONE }

    private static final Pattern DIGITS = Pattern.compile( "\\d{1,18}" );

    /** {@code active}, {@code none} or 1 to 18 digits; null or blank is no filter (null); anything else is refused. */
    public static SprintRef parse( final String wire ) {
        if ( wire == null || wire.isBlank() ) {
            return null;
        }
        final String v = wire.trim();
        if ( "active".equals( v ) ) {
            return new SprintRef( Kind.ACTIVE, null );
        }
        if ( "none".equals( v ) ) {
            return new SprintRef( Kind.NONE, null );
        }
        if ( DIGITS.matcher( v ).matches() ) {
            return new SprintRef( Kind.ID, Long.parseLong( v ) );
        }
        throw new WorkValidationException( "sprint", "sprint must be a sprint id, active or none." );
    }
}
