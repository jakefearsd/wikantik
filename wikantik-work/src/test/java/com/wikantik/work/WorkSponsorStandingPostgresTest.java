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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Through the service: an agent's claims end when its sponsor's standing does. A change the work module makes
 * itself (membership, takeover, release of sponsorship) ends them in that transaction; a change it is not told
 * about (a group, an account, the admin role) is caught by {@code sweepStanding}. codex is sponsored by lena, a
 * lead of PLAT.
 */
@RequiresPostgres
class WorkSponsorStandingPostgresTest {

    private final Actor root = Actor.human( "root", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor pat = Actor.human( "pat", Set.of(), false );

    private DataSource ds;
    private JdbcWorkStore store;
    private WorkService service;
    private long codex;
    private String token;
    private final Set< String > gone = new HashSet<>();
    private final Map< String, Set< String > > groups = new HashMap<>();
    private final Set< String > outageFor = new HashSet<>();
    private final List< ClaimOutcome > outcomes = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return groups.getOrDefault( login, Set.of() ); }
            @Override public boolean exists( final String login ) { return !gone.contains( login ); }
            @Override public String displayName( final String login ) { return "Dn " + login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return name.startsWith( "Dn " ) ? Optional.of( name.substring( 3 ) ) : Optional.empty();
            }
            @Override public Optional< Person > person( final String login ) {
                if ( outageFor.contains( login ) ) {
                    throw new WorkUnavailableException( "directory down" );
                }
                return gone.contains( login ) ? Optional.empty()
                    : Optional.of( new Person( login, groupsOf( login ), "root".equals( login ) ) );
            }
        }, AgentKeys.NONE, Clock.systemUTC(), WorkSettings.DEFAULTS, new WorkListener() {
            @Override public void claim( final ClaimOutcome o ) { outcomes.add( o ); }
        }, ( a, b, c, d, e ) -> { } );
        service.createProject( root, "PLAT", "Platform", "" );
        service.createProject( root, "OPS", "Operations", "" );
        service.putMember( root, "PLAT", "lena", "user", WorkRole.LEAD );
        service.putMember( root, "PLAT", "pat", "user", WorkRole.LEAD );
        service.putMember( root, "OPS", "lena", "user", WorkRole.LEAD );
        service.registry().createQueue( root, "implementer", "Implementer", "" );
        final WorkService.AgentWithToken made = service.registry().registerAgent( lena,
            new AgentDraft( "codex", "", "", List.of( "implementer" ) ) );
        codex = made.agent().id();
        token = made.token();
        service.putMember( lena, "PLAT", "codex", "agent", WorkRole.MEMBER );
        service.putMember( lena, "OPS", "codex", "agent", WorkRole.MEMBER );
    }

    private Actor agent() throws SQLException {
        return service.registry().actorForAgent( service.registry().agentForToken( token ).orElseThrow().agentId() );
    }

    /** A ready item in the project's implementer queue, claimed by codex. */
    private String claimIn( final String project ) throws SQLException {
        final String key = service.createItem( root, new ItemDraft( project, WorkLevel.TASK, null, null,
            WorkKind.SOFTWARE, "t", "", null, null, null, null ) ).item().key();
        final Item draft = service.item( root, key ).item();
        service.claims().handoff( root, key, new WorkClaimsService.Handoff( draft.version(), WorkStatus.READY, null,
            "implementer", "go" ) );
        service.claims().claim( agent(), key );
        outcomes.clear();
        return key;
    }

    private Long holder( final String key ) throws SQLException {
        return service.item( root, key ).item().claimedByAgent();
    }

    private List< WorkEvent > released( final String key ) throws SQLException {
        return store.itemEvents( service.item( root, key ).item().id() ).stream()
            .filter( e -> e.type().equals( WorkEvent.RELEASED ) ).toList();
    }

    // ---- changes the work module makes itself

    @Test
    void removingTheSponsorFromAProjectReleasesTheirAgentsClaimsThere() throws SQLException {
        final String plat = claimIn( "PLAT" );
        final String ops = claimIn( "OPS" );
        service.removeMember( pat, "PLAT", "Dn lena", "user" );
        assertNull( holder( plat ) );
        assertNotNull( holder( ops ), "the sponsor still leads OPS" );
        final WorkEvent e = released( plat ).get( 0 );
        assertEquals( "sponsor access removed", e.note() );
        assertEquals( "agent:" + codex, e.fromValue() );
        assertEquals( "pat", e.actor() );
        assertEquals( List.of( ClaimOutcome.RELEASED ), outcomes, "everyEndingIsCountedAsReleased" );
        assertThrows( WorkNotFoundException.class, () -> service.item( agent(), plat ),
            "and the agent no longer sees the project at all" );
    }

    @Test
    void demotingTheSponsorToViewerReleasesButToMemberDoesNot() throws SQLException {
        final String key = claimIn( "PLAT" );
        service.putMember( pat, "PLAT", "Dn lena", "user", WorkRole.MEMBER );
        assertNotNull( holder( key ) );
        assertTrue( outcomes.isEmpty() );
        service.putMember( pat, "PLAT", "Dn lena", "user", WorkRole.VIEWER );
        assertNull( holder( key ) );
        assertEquals( List.of( ClaimOutcome.RELEASED ), outcomes );
    }

    @Test
    void removingAGroupRowReleasesClaimsOfSponsorsWhoStoodOnlyOnThatGroup() throws SQLException {
        groups.put( "lena", Set.of( "Crew" ) );
        service.putMember( root, "PLAT", "Crew", "group", WorkRole.MEMBER );
        service.removeMember( root, "PLAT", "Dn lena", "user" );
        final String key = claimIn( "PLAT" );
        service.removeMember( pat, "PLAT", "Crew", "group" );
        assertNull( holder( key ) );
        assertEquals( "sponsor access removed", released( key ).get( 0 ).note() );
    }

    @Test
    void aMemberChangeDuringADirectoryOutageLeavesTheClaimForTheSweep() throws SQLException {
        final String key = claimIn( "PLAT" );
        outageFor.add( "lena" );
        service.removeMember( root, "PLAT", "pat", "user" ); // unrelated row; lena cannot be resolved right now
        assertNotNull( holder( key ), "a lookup that did not answer ends nothing" );
    }

    @Test
    void takeoverReleasesClaimsOutsideTheNewSponsorsReach() throws SQLException {
        final String plat = claimIn( "PLAT" );
        final String ops = claimIn( "OPS" );
        service.registry().releaseSponsorship( lena, codex );
        assertNull( holder( plat ) );
        assertNull( holder( ops ), "releaseOfSponsorshipReleasesEveryClaim" );
        assertEquals( List.of( ClaimOutcome.RELEASED, ClaimOutcome.RELEASED ), outcomes );
        service.registry().takeOver( pat, codex );
        final String again = claimIn( "PLAT" );
        assertNotNull( holder( again ), "under pat, a lead of PLAT, the agent works there again" );
        assertThrows( WorkNotFoundException.class, () -> service.item( agent(), ops ), "pat is not in OPS" );
    }

    @Test
    void anAdminTakeoverKeepsEveryClaim() throws SQLException {
        final String plat = claimIn( "PLAT" );
        service.registry().takeOver( root, codex );
        assertNotNull( holder( plat ) );
        assertTrue( outcomes.isEmpty() );
    }

    // ---- changes the work module is not told about: the sweep

    @Test
    void theSweepEndsClaimsAfterAnAccountDeletion() throws SQLException {
        final String plat = claimIn( "PLAT" );
        final String ops = claimIn( "OPS" );
        assertEquals( 0, service.claims().sweepStanding(), "nothing to end while the sponsor stands" );
        gone.add( "lena" );
        assertEquals( 2, service.claims().sweepStanding() );
        assertNull( holder( plat ) );
        assertNull( holder( ops ) );
        final WorkEvent e = released( plat ).get( 0 );
        assertEquals( "sponsor access removed", e.note() );
        assertEquals( Actor.Kind.AGENT, e.actorKind(), "a system ending is attributed to the agent, as expiry is" );
        assertEquals( codex, e.agentId() );
        assertEquals( List.of( ClaimOutcome.RELEASED, ClaimOutcome.RELEASED ), outcomes );
        assertEquals( 0, service.claims().sweepStanding(), "and it is done once" );
    }

    @Test
    void theSweepEndsClaimsAfterAGroupChangeTheModuleWasNotToldAbout() throws SQLException {
        groups.put( "lena", Set.of( "Crew" ) );
        service.putMember( root, "PLAT", "Crew", "group", WorkRole.MEMBER );
        service.removeMember( root, "PLAT", "Dn lena", "user" );
        final String plat = claimIn( "PLAT" );
        final String ops = claimIn( "OPS" );
        groups.remove( "lena" ); // she left the wiki group; no work row changed
        assertEquals( 1, service.claims().sweepStanding() );
        assertNull( holder( plat ) );
        assertNotNull( holder( ops ), "her own lead row in OPS still stands" );
    }

    @Test
    void theSweepEndsClaimsOfASponsorlessAgent() throws SQLException {
        final String plat = claimIn( "PLAT" );
        store.setSponsor( codex, null, "root" ); // as if a release had crashed before ending the claims
        assertEquals( 1, service.claims().sweepStanding() );
        assertNull( holder( plat ) );
    }

    @Test
    void aLookupFailureInTheSweepEndsNothing() throws SQLException {
        final String plat = claimIn( "PLAT" );
        gone.add( "lena" );
        outageFor.add( "lena" );
        assertEquals( 0, service.claims().sweepStanding() );
        assertEquals( 0, service.claims().sweepStanding() );
        assertNotNull( holder( plat ), "an outage is never read as lost standing" );
        outageFor.clear();
        assertEquals( 1, service.claims().sweepStanding(), "when the directory answers again, it is" );
    }
}
