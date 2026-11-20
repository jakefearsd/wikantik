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

/** Where a change is: open, in review, or closed by a merge or by being abandoned. */
public enum ChangeState implements WireEnum {
    OPEN("open"),
    IN_REVIEW("in_review"),
    MERGED("merged"),
    ABANDONED("abandoned");

    private final String wire;

    ChangeState( final String wire ) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    /** Open or in review: not yet merged or abandoned. */
    public boolean isLive() {
        return this == OPEN || this == IN_REVIEW;
    }

    /**
     * Whether a change in this state may move to {@code target}. Staying put is not a move (asking again for a
     * review, or approving one, leaves it in review); a merged or abandoned change never moves.
     */
    public boolean mayBecome( final ChangeState target ) {
        final Set< ChangeState > next = switch ( this ) {
            case OPEN -> EnumSet.of( IN_REVIEW, MERGED, ABANDONED );
            case IN_REVIEW -> EnumSet.of( OPEN, MERGED, ABANDONED );
            case MERGED, ABANDONED -> EnumSet.noneOf( ChangeState.class );
        };
        return next.contains( target );
    }

    /** Parses a stored value; throws {@link WorkValidationException} when unknown. */
    public static ChangeState fromWire( final String value ) {
        return WireEnum.parse( ChangeState.class, value, "state" );
    }
}
