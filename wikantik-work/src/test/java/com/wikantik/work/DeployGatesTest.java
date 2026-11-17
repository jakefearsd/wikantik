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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Who may approve a deployment under each gate. */
class DeployGatesTest {

    private static final String PERSON = "A person approves deployments of this product.";
    private static final String LEAD = "Approving this deployment takes a lead of every project it carries, or a wiki administrator.";

    /** who, gate, lead of every project, member of every project -> ok | PERSON | LEAD */
    @ParameterizedTest( name = "{0} under {1} lead={2} member={3} -> {4}" )
    @CsvSource( {
        "admin,human,false,false,ok",
        "person,human,true,true,ok",
        "person,human,false,true,LEAD",
        "person,human,false,false,LEAD",
        "agent,human,false,true,PERSON",
        "agent,human,true,true,PERSON",
        "admin,agent,false,false,ok",
        "person,agent,true,true,ok",
        "person,agent,false,true,ok",
        "person,agent,false,false,LEAD",
        "agent,agent,false,true,ok",
        "agent,agent,false,false,LEAD"
    } )
    void gateTable( final String who, final String gate, final boolean lead, final boolean member,
                    final String expected ) {
        final Actor actor = switch ( who ) {
            case "admin" -> Actor.human( "root", Set.of(), true );
            case "person" -> Actor.human( "alice", Set.of(), false );
            default -> Actor.agent( 7, "bot" );
        };
        final Decision d = DeployGates.mayApprove( actor, DeployGate.fromWire( gate ), lead, member );
        assertEquals( expected, d.allowed() ? "ok" : PERSON.equals( d.reason() ) ? "PERSON"
            : LEAD.equals( d.reason() ) ? "LEAD" : d.reason() );
    }
}
