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
package com.wikantik.api.knowledge;

import java.util.Set;

/**
 * Trust tier for materialised knowledge-graph rows. Monotonic: MACHINE
 * includes both machine- and human-vetted rows; HUMAN is the strict view.
 */
public enum Tier {
    HUMAN( "human", Set.of( "human" ) ),
    MACHINE( "machine", Set.of( "human", "machine" ) );

    private final String wireName;
    private final Set< String > includedTiers;

    Tier( final String wireName, final Set< String > includedTiers ) {
        this.wireName = wireName;
        this.includedTiers = includedTiers;
    }

    public String wireName() { return wireName; }

    public boolean includes( final String tierColumnValue ) {
        return includedTiers.contains( tierColumnValue );
    }

    public Set< String > includedTiers() { return includedTiers; }

    public static Tier fromWire( final String wire ) {
        if ( wire == null ) throw new IllegalArgumentException( "tier must not be null" );
        for ( final Tier t : values() ) {
            if ( t.wireName.equalsIgnoreCase( wire ) ) return t;
        }
        throw new IllegalArgumentException( "unknown tier: " + wire );
    }
}
