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

/** Which actors may write what to the store: the rules the system (code-host) actor adds to the attribution rules. */
final class ActorRules {

    private ActorRules() {
    }

    /**
     * What an event row may carry: the rules of {@link JdbcWorkCatalog#requireAttributed}, except that a system event
     * is allowed (and carries no agent id). Every other writer refuses the system actor.
     */
    static void requireEventAttribution( final Actor.Kind kind, final Long agentId ) {
        if ( kind == Actor.Kind.SYSTEM ) {
            if ( agentId != null ) {
                throw new IllegalArgumentException( "a system event carries no work_agent id" );
            }
            return;
        }
        JdbcWorkCatalog.requireAttributed( kind, agentId );
    }

    /** Refuses every actor but the code host; the code-host writers call this first. */
    static void requireSystem( final Actor by ) {
        if ( by.kind() != Actor.Kind.SYSTEM ) {
            throw new IllegalArgumentException( "only the code-host actor may write code-host changes" );
        }
    }
}
