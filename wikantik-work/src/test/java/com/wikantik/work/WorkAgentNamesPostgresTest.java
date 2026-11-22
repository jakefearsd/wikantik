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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** An agent never takes the name of a person (so a notice or mail for that person can never be taken for the agent's). */
@RequiresPostgres
class WorkAgentNamesPostgresTest {

    private static final Actor LENA = NotifyServiceFixture.LENA;

    private NotifyServiceFixture fx;
    private WorkRegistryService registry;

    @BeforeEach
    void setUp() throws SQLException {
        fx = new NotifyServiceFixture();
        registry = fx.service.registry();
        fx.accounts.add( "alice" );
    }

    private static AgentDraft named( final String name ) {
        return new AgentDraft( name, "", "", List.of() );
    }

    @Test
    void registeringAPersonsNameIsRefused() throws SQLException {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> registry.registerAgent( LENA, named( "alice" ) ) );
        assertEquals( "name", e.field() );
        assertTrue( fx.store.agentByName( "alice" ).isEmpty() );
        assertTrue( registry.registerAgent( LENA, named( "scribe" ) ).token().startsWith( "wka_" ) );
    }

    @Test
    void theMatchIsCaseInsensitive() {
        for ( final String name : new String[] { "Alice", "ALICE", "  aLiCe " } ) {
            assertThrows( WorkValidationException.class, () -> registry.registerAgent( LENA, named( name ) ), name );
        }
    }

    @Test
    void renamingToAPersonsNameIsRefused() throws SQLException {
        final long id = registry.registerAgent( LENA, named( "scribe" ) ).agent().id();
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> registry.updateAgent( LENA, id, named( "Alice" ) ) );
        assertEquals( "name", e.field() );
        assertEquals( "scribe", fx.store.agent( id ).orElseThrow().name() );
        registry.updateAgent( LENA, id, named( "scribe-2" ) );
        assertEquals( "scribe-2", fx.store.agent( id ).orElseThrow().name() );
    }

    @Test
    void anAgentThatAlreadyShareAPersonsNameCanStillBeEditedWithoutRenaming() throws SQLException {
        final long id = registry.registerAgent( LENA, named( "carol" ) ).agent().id();
        fx.accounts.add( "carol" );   // the person arrived later
        registry.updateAgent( LENA, id, new AgentDraft( null, "Acme", null, null ) );
        assertEquals( "Acme", fx.store.agent( id ).orElseThrow().vendor() );
    }

    @Test
    void aDirectoryOutageRefusesTheRegistration() throws SQLException {
        fx.directoryDown = true;
        assertThrows( WorkUnavailableException.class, () -> registry.registerAgent( LENA, named( "scribe" ) ) );
        assertTrue( fx.store.agentByName( "scribe" ).isEmpty(), "fails closed: nothing was registered" );
    }

    /** The stub matches {@code exists} and {@code person} exactly, like the real directory: only {@code loginIgnoringCase} sees variants. */
    @Test
    void anAgentNameThatDiffersFromAnAccountsLoginOnlyByCaseIsRefused() throws SQLException {
        fx.accounts.add( "Bella" );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> registry.registerAgent( LENA, named( "bella" ) ) );
        assertEquals( "name", e.field() );
        assertTrue( fx.store.agentByName( "bella" ).isEmpty() );
        final long id = registry.registerAgent( LENA, named( "scribe" ) ).agent().id();
        assertThrows( WorkValidationException.class, () -> registry.updateAgent( LENA, id, named( "bella" ) ) );
        assertEquals( "scribe", fx.store.agent( id ).orElseThrow().name() );
    }
}
