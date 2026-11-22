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

/**
 * Project role; declaration order is privilege order, lowest first.
 *
 * <p>{@link #REPORTER} files items and sees only the ones they reported; it gives no standing in the project (see
 * {@code WorkStanding}). It sorts below every role that does.
 */
public enum WorkRole implements WireEnum {
    REPORTER("reporter"),
    VIEWER("viewer"),
    MEMBER("member"),
    LEAD("lead");

    /**
     * The roles that give standing in a project (seeing it, and acting by the policy), named one by one: a role added
     * later gives none until it is listed here. {@code WorkStanding} filters on it and the visible-projects SQL is
     * built from it ({@code JdbcWorkCatalog.VISIBLE_PROJECTS_SQL}), so the two cannot disagree.
     */
    public static final java.util.Set< WorkRole > STANDING = java.util.Collections.unmodifiableSet(
        java.util.EnumSet.of( VIEWER, MEMBER, LEAD ) );

    private final String wire;

    WorkRole( final String wire ) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    /** Parses a stored value; throws {@link WorkValidationException} when unknown. */
    public static WorkRole fromWire( final String value ) {
        return WireEnum.parse( WorkRole.class, value, "role" );
    }

    /** True when this role is at least as privileged as {@code other}. */
    public boolean atLeast( final WorkRole other ) {
        return compareTo( other ) >= 0;
    }

    /** The most privileged of the given roles, or null when none. */
    public static WorkRole highest( final java.util.Collection<WorkRole> roles ) {
        WorkRole best = null;
        for ( final WorkRole role : roles ) {
            if ( role != null && ( best == null || role.compareTo( best ) > 0 ) ) {
                best = role;
            }
        }
        return best;
    }
}
