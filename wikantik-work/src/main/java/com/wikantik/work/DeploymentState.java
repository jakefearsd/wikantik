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

import java.util.EnumSet;
import java.util.Set;

/** Where a deployment is. It only moves forward: a failed deployment is retried as a new one. */
public enum DeploymentState {
    REQUESTED("requested"),
    APPROVED("approved"),
    RUNNING("running"),
    VERIFIED("verified"),
    FAILED("failed"),
    ROLLED_BACK("rolled_back");

    private final String wire;

    DeploymentState( final String wire ) {
        this.wire = wire;
    }

    /** The lowercase value as stored in the database. */
    public String wire() {
        return wire;
    }

    /** Whether a deployment in this state may move to {@code target}. */
    public boolean mayBecome( final DeploymentState target ) {
        final Set< DeploymentState > next = switch ( this ) {
            case REQUESTED -> EnumSet.of( APPROVED );
            case APPROVED -> EnumSet.of( RUNNING );
            case RUNNING -> EnumSet.of( VERIFIED, FAILED );
            case VERIFIED, FAILED -> EnumSet.of( ROLLED_BACK );
            case ROLLED_BACK -> EnumSet.noneOf( DeploymentState.class );
        };
        return next.contains( target );
    }

    /** Whether a deploy script may go ahead: approved and not yet finished. */
    public boolean clearedToRun() {
        return this == APPROVED || this == RUNNING;
    }

    /** Parses a stored value; throws {@link WorkValidationException} when unknown. */
    public static DeploymentState fromWire( final String value ) {
        for ( final DeploymentState candidate : values() ) {
            if ( candidate.wire.equals( value ) ) {
                return candidate;
            }
        }
        throw new WorkValidationException( "state", "Unknown state: " + value );
    }
}
