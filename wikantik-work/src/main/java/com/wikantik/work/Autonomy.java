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

/** How much agents may do unattended. Declared strictest first: the order is the scale. */
public enum Autonomy implements WireEnum {
    SUPERVISED("supervised"),
    DELEGATED("delegated"),
    AUTONOMOUS("autonomous");

    private final String wire;

    Autonomy( final String wire ) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    /** The stricter of the two; a null counts as "no limit" and yields the other. */
    public static Autonomy strictest( final Autonomy a, final Autonomy b ) {
        if ( a == null ) {
            return b;
        }
        if ( b == null ) {
            return a;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    /** Whether this level lets an agent do more than {@code other}. */
    public boolean looserThan( final Autonomy other ) {
        return ordinal() > other.ordinal();
    }

    /** Parses a stored value; throws {@link WorkValidationException} when unknown. */
    public static Autonomy fromWire( final String value ) {
        return WireEnum.parse( Autonomy.class, value, "autonomy" );
    }
}
