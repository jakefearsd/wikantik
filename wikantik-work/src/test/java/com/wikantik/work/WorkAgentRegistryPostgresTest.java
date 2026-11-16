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
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent registry on sponsors and credentials: who may register, edit, rotate, retire, take over and release an
 * agent, and who can see it. PLAT is led by lena and pat, with mia a member; OPS is led by bo; zed is in nothing.
 */
@RequiresPostgres
class WorkAgentRegistryPostgresTest {

    private final Actor root = Actor.human( "root", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor pat = Actor.human( "pat", Set.of(), false );
    private final Actor mia = Actor.human( "mia", Set.of(), false );
    private final Actor bo = Actor.human( "bo", Set.of(), false );
    private final Actor zed = Actor.human( "zed", Set.of(), false );

    private DataSource ds;
    private JdbcWorkStore store;
    private WorkService service;
    private final Set< String > gone = new HashSet<>();
    private boolean outage;
    private RuntimeException auditFails;
    /** What reached the admin audit trail: {@code actor|action|target|detail}. */
    private final List< String > audited = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return !gone.contains( login ); }
            @Override public String displayName( final String login ) { return "Dn " + login; }
            @Override public Optional< Person > person( final String login ) {
                if ( outage ) {
                    throw new WorkUnavailableException( "directory down" );
                }
                return gone.contains( login ) ? Optional.empty()
                    : Optional.of( new Person( login, Set.of(), "root".equals( login ) ) );
            }
        }, Clock.systemUTC(), WorkSettings.DEFAULTS, null, ( actor, action, type, target, detail ) -> {
            if ( auditFails != null ) {
                throw auditFails;
            }
            audited.add( actor + "|" + action + "|" + target + "|" + detail );
        } );
        service.createProject( root, "PLAT", "Platform", "" );
        service.createProject( root, "OPS", "Operations", "" );
        service.putMember( root, "PLAT", "lena", "user", WorkRole.LEAD );
        service.putMember( root, "PLAT", "pat", "user", WorkRole.LEAD );
        service.putMember( root, "PLAT", "mia", "user", WorkRole.MEMBER );
        service.putMember( root, "OPS", "bo", "user", WorkRole.LEAD );
        service.registry().createQueue( root, "implementer", "Implementer", "" );
        service.registry().createQueue( root, "reviewer", "Reviewer", "" );
        audited.clear();
    }

    private static AgentDraft draft( final String name ) {
        return new AgentDraft( name, "acme", "cli", List.of( "implementer" ) );
    }

    /** codex, registered by lena and admitted to PLAT. */
    private WorkService.AgentWithToken codexInPlat() throws SQLException {
        final WorkService.AgentWithToken made = service.registry().registerAgent( lena, draft( "codex" ) );
        service.putMember( lena, "PLAT", "codex", "agent", WorkRole.MEMBER );
        return made;
    }

    private Actor actorOf( final String token ) throws SQLException {
        final Optional< WorkRegistryService.AgentLogin > login = service.registry().agentForToken( token );
        return login.isEmpty() ? null : service.registry().actorForAgent( login.get().agentId() );
    }

    private String sponsorOf( final long agentId ) throws SQLException {
        return store.agent( agentId ).orElseThrow().sponsor();
    }

    private static String denial( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkDeniedException.class, call ).getMessage();
    }

    // ---- register

    @Test
    void aLeadRegistersAnAgentAndBecomesItsSponsor() throws SQLException {
        final WorkService.AgentWithToken made = service.registry().registerAgent( lena, draft( "Codex" ) );
        assertEquals( "codex", made.agent().name() );
        assertEquals( "lena", made.agent().sponsor() );
        assertEquals( List.of( "implementer" ), made.agent().queues() );
        assertTrue( AgentTokens.looksLikeAgentToken( made.token() ) );
        assertEquals( Actor.agent( made.agent().id(), "codex", lena ), actorOf( made.token() ) );
        assertEquals( "lena", store.sponsorHistory( made.agent().id() ).get( 0 ).sponsor() );
        assertEquals( List.of( "lena|work.agent.register|codex|null" ), audited );
    }


    @Test
    void aMemberWhoLeadsNothingCannotRegister() {
        final String text = "Only a project lead or a wiki administrator may register an agent.";
        assertEquals( text, denial( () -> service.registry().registerAgent( mia, draft( "codex" ) ) ) );
        assertEquals( text, denial( () -> service.registry().registerAgent( zed, draft( "codex" ) ) ) );
    }

    @Test
    void aLeadOfOnlyAnArchivedProjectCannotRegister() throws SQLException {
        service.archiveProject( root, "OPS" );
        assertThrows( WorkDeniedException.class, () -> service.registry().registerAgent( bo, draft( "codex" ) ) );
    }

    @Test
    void anAgentCannotRegisterAnAgent() throws SQLException {
        final WorkService.AgentWithToken codex = codexInPlat();
        assertThrows( WorkDeniedException.class,
            () -> service.registry().registerAgent( actorOf( codex.token() ), draft( "child" ) ) );
    }

    @Test
    void aDuplicateNameIsAConflictAndIssuesNothing() throws SQLException {
        service.registry().registerAgent( lena, draft( "codex" ) );
        assertThrows( WorkConflictException.class, () -> service.registry().registerAgent( bo, draft( "CODEX" ) ) );
        assertEquals( 1, WorkAgentFixtures.scalar( ds, "SELECT count(*) FROM work_agent_credential" ) );
    }

    // ---- rotate, retire, edit

    @Test
    void rotateCredentialRevokesTheOldToken() throws SQLException {
        final WorkService.AgentWithToken made = codexInPlat();
        final WorkService.AgentWithToken rotated = service.registry().rotateCredential( lena, made.agent().id() );
        assertNotEquals( made.token(), rotated.token() );
        assertNull( actorOf( made.token() ), "the old token is nobody from the next request" );
        assertNotNull( actorOf( rotated.token() ) );
        assertTrue( audited.contains( "lena|work.agent.rotate_key|codex|null" ) );
    }

    @Test
    void onlyTheSponsorOrAnAdminRotatesOrRetires() throws SQLException {
        final long id = codexInPlat().agent().id();
        final String text = "Only this agent's sponsor or a wiki administrator may do that.";
        assertEquals( text, denial( () -> service.registry().rotateCredential( pat, id ) ),
            "another lead of the same project is not its sponsor" );
        assertEquals( text, denial( () -> service.registry().retireAgent( mia, id ) ) );
        assertEquals( text, denial( () -> service.registry().updateAgent( pat, id, draft( "codex" ) ) ) );
        assertEquals( text, denial( () -> service.registry().releaseSponsorship( pat, id ) ) );
        assertNotNull( service.registry().rotateCredential( root, id ).token() );
        service.registry().retireAgent( lena, id );
        assertTrue( store.agent( id ).orElseThrow().retiredAt() != null );
    }

    @Test
    void anAgentTheyCannotSeeIsNotFoundNotDenied() throws SQLException {
        final long id = codexInPlat().agent().id();
        for ( final Actor stranger : List.of( bo, zed ) ) {
            assertThrows( WorkNotFoundException.class, () -> service.registry().rotateCredential( stranger, id ) );
            assertThrows( WorkNotFoundException.class, () -> service.registry().retireAgent( stranger, id ) );
            assertThrows( WorkNotFoundException.class, () -> service.registry().updateAgent( stranger, id, draft( "x" ) ) );
            assertThrows( WorkNotFoundException.class, () -> service.registry().takeOver( stranger, id ) );
            assertThrows( WorkNotFoundException.class, () -> service.registry().releaseSponsorship( stranger, id ) );
        }
        assertThrows( WorkNotFoundException.class, () -> service.registry().rotateCredential( root, 999_999 ) );
    }

    @Test
    void retireRevokesTheCredentialAndIsRepeatable() throws SQLException {
        final WorkService.AgentWithToken made = codexInPlat();
        service.registry().retireAgent( lena, made.agent().id() );
        assertTrue( service.registry().agentForToken( made.token() ).isEmpty() );
        service.registry().retireAgent( root, made.agent().id() );
        assertThrows( WorkNotFoundException.class, () -> service.registry().rotateCredential( lena, made.agent().id() ),
            "a retired agent is not rotated back to life" );
    }

    @Test
    void aSponsorEditsTheirAgentAndOmittedFieldsStay() throws SQLException {
        final long id = codexInPlat().agent().id();
        service.registry().updateAgent( lena, id, new AgentDraft( null, null, null, List.of( "reviewer" ) ) );
        final Agent after = store.agent( id ).orElseThrow();
        assertEquals( List.of( "reviewer" ), after.queues() );
        assertEquals( "acme", after.vendor() );
        assertEquals( "codex", after.name() );
        service.registry().updateAgent( root, id, new AgentDraft( "Codex-2", null, null, null ) );
        assertEquals( "codex-2", store.agent( id ).orElseThrow().name() );
        assertTrue( audited.contains( "root|work.agent.update|codex-2|renamed from codex" ) );
    }

    @Test
    void aDemotedSponsorCanStillRotateAndRetireButNotEdit() throws SQLException {
        final long id = codexInPlat().agent().id();
        service.putMember( root, "PLAT", "lena", "user", WorkRole.MEMBER );
        assertEquals( "Editing an agent takes a project lead or a wiki administrator.",
            denial( () -> service.registry().updateAgent( lena, id, draft( "codex" ) ) ) );
        assertNotNull( service.registry().rotateCredential( lena, id ).token(), "rotating only reduces risk" );
        service.registry().retireAgent( lena, id );
    }

    // ---- sponsorship

    @Test
    void anAdminTakesOverAtAnyTime() throws SQLException {
        final long id = codexInPlat().agent().id();
        assertEquals( "root", service.registry().takeOver( root, id ).sponsor() );
        assertEquals( "root", sponsorOf( id ) );
    }

    @Test
    void aLeadCannotDisplaceALivingSponsor() throws SQLException {
        final long id = codexInPlat().agent().id();
        assertEquals( "This agent has a sponsor. Ask them to release it, or ask a wiki administrator.",
            denial( () -> service.registry().takeOver( pat, id ) ) );
        assertEquals( "lena", sponsorOf( id ) );
    }

    @Test
    void releaseSponsorshipLeavesTheAgentNoActor() throws SQLException {
        final WorkService.AgentWithToken made = codexInPlat();
        service.registry().releaseSponsorship( lena, made.agent().id() );
        assertNull( sponsorOf( made.agent().id() ) );
        assertNull( actorOf( made.token() ), "the token still verifies, but nobody stands behind the agent" );
    }

    @Test
    void aLeadTakesOverOnlyASponsorlessAgentInTheirProject() throws SQLException {
        final WorkService.AgentWithToken made = codexInPlat();
        final long id = made.agent().id();
        service.registry().releaseSponsorship( lena, id );
        assertEquals( "Taking over an agent takes a lead of a project it is in, or a wiki administrator.",
            denial( () -> service.registry().takeOver( mia, id ) ) );
        assertThrows( WorkNotFoundException.class, () -> service.registry().takeOver( bo, id ) );
        assertEquals( "pat", service.registry().takeOver( pat, id ).sponsor() );
        assertEquals( Actor.agent( id, "codex", pat ), actorOf( made.token() ), "the same token, a new sponsor" );
    }

    @Test
    void anAgentWhoseSponsorsAccountIsGoneCanBeTakenOverByALead() throws SQLException {
        final long id = codexInPlat().agent().id();
        gone.add( "lena" );
        assertEquals( "pat", service.registry().takeOver( pat, id ).sponsor() );
    }

    @Test
    void anAgentCannotTakeOverOrRelease() throws SQLException {
        final WorkService.AgentWithToken codex = codexInPlat();
        final Actor self = actorOf( codex.token() );
        assertThrows( WorkDeniedException.class, () -> service.registry().takeOver( self, codex.agent().id() ) );
        assertThrows( WorkDeniedException.class, () -> service.registry().releaseSponsorship( self, codex.agent().id() ) );
    }

    @Test
    void takeoverAndReleaseWriteHistoryAndAudit() throws SQLException {
        final long id = codexInPlat().agent().id();
        audited.clear();
        service.registry().releaseSponsorship( lena, id );
        service.registry().takeOver( pat, id );
        assertEquals( java.util.Arrays.asList( "pat", null, "lena" ),
            store.sponsorHistory( id ).stream().map( WorkRegistryStore.SponsorChange::sponsor ).toList() );
        assertEquals( List.of( "pat", "lena", "lena" ),
            store.sponsorHistory( id ).stream().map( WorkRegistryStore.SponsorChange::changedBy ).toList() );
        assertEquals( List.of( "lena|work.agent.sponsor_released|codex|null", "pat|work.agent.takeover|codex|null" ),
            audited );
    }

    @Test
    void aDirectoryOutageDuringTakeoverIsUnavailableAndChangesNothing() throws SQLException {
        final long id = codexInPlat().agent().id();
        outage = true;
        assertThrows( WorkUnavailableException.class, () -> service.registry().takeOver( pat, id ) );
        outage = false;
        assertEquals( "lena", sponsorOf( id ) );
    }

    @Test
    void aFailingAuditNeverUndoesTheChange() throws SQLException {
        final long id = codexInPlat().agent().id();
        auditFails = new IllegalStateException( "audit store down" );
        service.registry().releaseSponsorship( lena, id );
        assertNull( sponsorOf( id ) );
    }

    // ---- who sees which agents

    @Test
    void aNonAdminSeesOnlyAgentsTheySponsorOrShareAProjectWith() throws SQLException {
        codexInPlat();
        service.registry().registerAgent( bo, draft( "ops-bot" ) );
        service.registry().registerAgent( lena, draft( "spare" ) ); // in no project
        final java.util.function.Function< Actor, List< String > > names = who -> {
            try {
                return service.registry().listAgents( who ).stream().map( Agent::name ).toList();
            } catch ( final SQLException e ) {
                throw new IllegalStateException( e );
            }
        };
        assertEquals( List.of( "codex", "ops-bot", "spare" ), names.apply( root ) );
        assertEquals( List.of( "codex", "spare" ), names.apply( lena ) );
        assertEquals( List.of( "codex" ), names.apply( mia ), "a member sees the agents in their project" );
        assertEquals( List.of( "ops-bot" ), names.apply( bo ) );
        assertEquals( List.of(), names.apply( zed ) );
    }

    @Test
    void viewsCarryTheSponsorsNameAndAPlainStatus() throws SQLException {
        final long codex = codexInPlat().agent().id();
        final long spare = service.registry().registerAgent( lena, draft( "spare" ) ).agent().id();
        final long old = service.registry().registerAgent( lena, draft( "old" ) ).agent().id();
        service.registry().releaseSponsorship( lena, spare );
        service.registry().retireAgent( lena, old );
        final java.util.Map< String, WorkRegistryService.AgentView > byName = new java.util.HashMap<>();
        for ( final WorkRegistryService.AgentView v : service.registry().agentViews( root ) ) {
            byName.put( v.agent().name(), v );
        }
        assertEquals( WorkRegistryService.AgentStatus.ACTIVE, byName.get( "codex" ).status() );
        assertEquals( "Dn lena", byName.get( "codex" ).sponsorName() );
        assertEquals( WorkRegistryService.AgentStatus.NO_SPONSOR, byName.get( "spare" ).status() );
        assertNull( byName.get( "spare" ).sponsorName() );
        assertEquals( WorkRegistryService.AgentStatus.RETIRED, byName.get( "old" ).status() );
        gone.add( "lena" );
        final WorkRegistryService.AgentView orphan = service.registry().agentViews( root ).stream()
            .filter( v -> v.agent().id() == codex ).findFirst().orElseThrow();
        assertEquals( WorkRegistryService.AgentStatus.SPONSOR_GONE, orphan.status() );
    }

    // ---- what a project's member list says about an agent row

    @Test
    void anAgentMemberRowSaysWhetherItHasAnyEffectAndWhyNot() throws SQLException {
        final long codex = codexInPlat().agent().id();
        final java.util.function.Supplier< WorkRegistryService.MemberStanding > standing = () -> {
            try {
                return service.registry().agentStandings( mia, "PLAT" ).get( codex );
            } catch ( final SQLException e ) {
                throw new IllegalStateException( e );
            }
        };
        assertEquals( WorkRegistryService.MemberStanding.EFFECTIVE, standing.get() );
        service.putMember( root, "PLAT", "lena", "user", WorkRole.VIEWER );
        assertEquals( WorkRegistryService.MemberStanding.SPONSOR_VIEWER, standing.get() );
        service.removeMember( root, "PLAT", "lena", "user" );
        assertEquals( WorkRegistryService.MemberStanding.SPONSOR_NOT_MEMBER, standing.get() );
        gone.add( "lena" );
        assertEquals( WorkRegistryService.MemberStanding.SPONSOR_GONE, standing.get() );
        gone.clear();
        service.registry().releaseSponsorship( root, codex );
        assertEquals( WorkRegistryService.MemberStanding.NO_SPONSOR, standing.get() );
        service.registry().takeOver( root, codex );
        assertEquals( WorkRegistryService.MemberStanding.EFFECTIVE, standing.get(), "an admin sponsor needs no row" );
        assertThrows( WorkNotFoundException.class, () -> service.registry().agentStandings( zed, "PLAT" ),
            "a stranger learns nothing, as for the member list itself" );
    }

    @Test
    void aDirectoryOutageLeavesAnAgentRowsStandingUnsaid() throws SQLException {
        final long codex = codexInPlat().agent().id();
        outage = true;
        assertTrue( !service.registry().agentStandings( mia, "PLAT" ).containsKey( codex ),
            "nothing is claimed about an agent whose sponsor could not be looked up" );
    }
}
