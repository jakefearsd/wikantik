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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A member row with a blank principal could be matched by any actor whose own name binds as blank (an agent with no id,
 * a person with an empty login). The table refuses such a row, and the store never binds a blank value as a match.
 */
@RequiresPostgres
class WorkBlankPrincipalPostgresTest {

    private WorkClaimFixtures fx;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( PostgresTestDb.createDataSource() );
    }

    @Test
    void theTableRefusesABlankPrincipalOfAnyKind() {
        for ( final String kind : new String[] { "user", "group", "agent" } ) {
            for ( final String blank : new String[] { "", "   ", "\t", "\n " } ) {
                assertThrows( SQLException.class, () -> WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_member "
                    + "(project_id, principal_kind, principal, role) VALUES (" + fx.plat.id() + ", '" + kind + "', '"
                    + blank + "', 'viewer')" ), kind + " [" + blank + "]" );
            }
        }
    }

    @Test
    void thePutMemberWriterRefusesABlankPrincipalBeforeTheTable() {
        assertThrows( WorkValidationException.class,
            () -> fx.store.putMember( fx.plat.id(), " ", "user", WorkRole.VIEWER, WorkClaimFixtures.LENA ) );
    }

    @Test
    void aBlankNameIsNeverBoundAsAMatch() {
        assertNull( JdbcWorkMembers.userPrincipal( Actor.human( "  ", Set.of(), false ) ) );
        assertNull( JdbcWorkMembers.userPrincipal( Actor.human( null, Set.of(), false ) ) );
        assertNull( JdbcWorkMembers.agentPrincipal( Actor.human( "lena", Set.of(), false ) ) );
        assertEquals( "lena", JdbcWorkMembers.userPrincipal( Actor.human( "lena", Set.of(), false ) ) );
        assertArrayEquals( new String[] { "ops" },
            JdbcWorkMembers.groupArray( Actor.human( "lena", Set.of( "ops", "", " " ), false ) ) );
        assertTrue( JdbcWorkMembers.agentPrincipal( fx.actorA ).matches( "\\d+" ) );
    }
}
