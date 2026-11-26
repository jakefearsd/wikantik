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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WIKIENG-3: the person who creates a project becomes its first lead, in the creation transaction, so the same
 * person acting without admin rights (their API key, which never acts as an admin) sees the project at once.
 */
@RequiresPostgres
class WorkProjectCreatorLeadPostgresTest {

    private GateFixture f;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
    }

    @Test
    void theCreatorIsTheFirstLeadWithOneMemberEvent() throws SQLException {
        f.svc.createProject( f.admin, "OPS", "Operations", "" );

        final List< Member > members = f.svc.members( f.admin, "OPS" );
        assertEquals( 1, members.size(), "the creator and nobody else" );
        assertEquals( "user", members.get( 0 ).principalKind() );
        assertEquals( "admin", members.get( 0 ).principal() );
        assertEquals( WorkRole.LEAD, members.get( 0 ).role() );

        final List< WorkEvent > memberEvents = f.svc.activity( f.admin, "OPS", null, 50 ).stream()
            .filter( e -> WorkEvent.MEMBER.equals( e.type() ) ).toList();
        assertEquals( 1, memberEvents.size() );
        assertEquals( "user:admin:lead", memberEvents.get( 0 ).toValue() );
        assertEquals( "admin", memberEvents.get( 0 ).actor() );
    }

    @Test
    void theCreatorsKeyListsTheProjectAsLeadImmediately() throws SQLException {
        f.svc.createProject( f.admin, "OPS", "Operations", "" );
        final Actor adminsKey = Actor.human( "admin", Set.of(), false );

        assertTrue( f.svc.listProjects( adminsKey ).stream().anyMatch( p -> "OPS".equals( p.key() ) ) );
        assertTrue( f.svc.listProjectsWithRoles( adminsKey ).stream()
            .anyMatch( r -> "OPS".equals( r.project().key() ) && r.role() == WorkRole.LEAD ) );
    }

    @Test
    void aRefusedCreationLeavesNoMemberBehind() throws SQLException {
        f.svc.createProject( f.admin, "OPS", "Operations", "" );
        final Actor otherAdmin = Actor.human( "root", Set.of(), true );
        assertThrows( WorkConflictException.class, () -> f.svc.createProject( otherAdmin, "OPS", "Again", "" ) );
        assertEquals( List.of( "admin" ), f.svc.members( f.admin, "OPS" ).stream().map( Member::principal ).toList() );
    }
}
