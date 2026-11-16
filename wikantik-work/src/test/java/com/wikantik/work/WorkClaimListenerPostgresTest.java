package com.wikantik.work;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** On a real database, what the claim listener heard is exactly what the event log recorded. */
@RequiresPostgres
class WorkClaimListenerPostgresTest {

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final List< ClaimOutcome > heard = new ArrayList<>();
    private DataSource ds;
    private WorkService svc;
    private Agent agentA;
    private Agent agentB;
    private Actor a;
    private Actor b;
    private int seq;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final AgentKeys keys = new AgentKeys() {
            @Override
            public Minted mint( final String ownerLogin, final String label ) {
                try {
                    return new Minted( ( int ) WorkAgentFixtures.apiKey( ds, "work" ), "tok" );
                } catch ( final SQLException e ) {
                    throw new IllegalStateException( e );
                }
            }

            @Override
            public Minted remint( final int oldKeyId, final String label ) {
                return mint( null, label );
            }

            @Override
            public void revoke( final int keyId, final String revokedBy ) {
            }
        };
        svc = new WorkService( new JdbcWorkStore( ds ), md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return "Lena Lead".equals( name ) ? Optional.of( "lena" ) : Optional.empty();
            }
        }, keys, Clock.systemUTC(), WorkSettings.DEFAULTS, new WorkListener() {
            @Override public void claim( final ClaimOutcome o ) { heard.add( o ); }
        } );
        for ( final String project : List.of( "PLAT", "OPS" ) ) {
            svc.createProject( admin, project, project, "" );
            svc.putMember( admin, project, "lena", "user", WorkRole.LEAD );
        }
        svc.registry().createQueue( admin, "implementer", "Implementer", "" );
        agentA = svc.registry().registerAgent( admin, new AgentDraft( "agent-a", "", "", List.of( "implementer" ),
            "lena" ) ).agent();
        agentB = svc.registry().registerAgent( admin, new AgentDraft( "agent-b", "", "", List.of( "implementer" ),
            "lena" ) ).agent();
        a = Actor.agent( agentA.id(), agentA.name() );
        b = Actor.agent( agentB.id(), agentB.name() );
        svc.putMember( admin, "PLAT", "agent-a", "agent", WorkRole.MEMBER );
        svc.putMember( admin, "OPS", "agent-a", "agent", WorkRole.MEMBER );
        svc.putMember( admin, "PLAT", "agent-b", "agent", WorkRole.MEMBER );
        heard.clear();
    }

    /** A ready item in the implementer queue. */
    private String ready( final String project ) throws SQLException {
        final Item draft = svc.createItem( lena, new ItemDraft( project, WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "t" + ++seq, "", null, null, null, "implementer" ) ).item();
        return svc.updateItem( lena, draft.key(), new ItemPatch( draft.version(), Map.of( "status", "ready" ), null ) )
            .item().key();
    }

    private long count( final ClaimOutcome o ) {
        return heard.stream().filter( x -> x == o ).count();
    }

    private long logged( final String type ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_event WHERE type = '" + type + "'" );
    }

    @Test
    void removingAnAgentMemberReportsOnlyThatProjectsClaims() throws SQLException {
        svc.claims().claim( a, ready( "PLAT" ) );
        svc.claims().claim( a, ready( "PLAT" ) );
        svc.claims().claim( a, ready( "OPS" ) );
        heard.clear();
        svc.removeMember( admin, "PLAT", "agent-a", "agent" );
        assertEquals( List.of( ClaimOutcome.RELEASED, ClaimOutcome.RELEASED ), heard );
        assertEquals( 1, svc.claims().myClaims( a ).size(), "the OPS claim is untouched" );
    }

    @Test
    void demotingToViewerAndRetiringReportTheirReleases() throws SQLException {
        svc.claims().claim( a, ready( "PLAT" ) );
        svc.claims().claim( a, ready( "OPS" ) );
        svc.claims().claim( b, ready( "PLAT" ) );
        heard.clear();
        svc.putMember( admin, "PLAT", "agent-a", "agent", WorkRole.VIEWER );
        assertEquals( 1, heard.size() );
        svc.registry().retireAgent( admin, agentA.id() );
        assertEquals( 2, heard.size(), "the OPS claim ends with the retirement" );
        svc.registry().retireAgent( admin, agentA.id() );
        assertEquals( 2, heard.size(), "a repeat retire releases nothing" );
    }

    @Test
    void listenerCountsEqualTheEventLog() throws SQLException {
        final String one = ready( "PLAT" );
        svc.claims().claim( a, one );
        svc.claims().claim( a, one );
        assertThrows( WorkConflictException.class, () -> svc.claims().claim( b, one ) );
        final String two = ready( "PLAT" );
        svc.claims().claim( a, two );
        svc.claims().release( a, two, "stuck" );
        final String three = ready( "PLAT" );
        final Item held = svc.claims().claim( a, three ).view().item();
        svc.claims().handoff( lena, three, new WorkClaimsService.Handoff( held.version(), WorkStatus.REVIEW,
            "Lena Lead", null, "yours" ) );
        final String four = ready( "PLAT" );
        final Item moved = svc.claims().claim( a, four ).view().item();
        svc.updateItem( lena, four, new ItemPatch( moved.version(), Map.of( "status", "refining" ), null ) );
        svc.claims().claim( a, ready( "PLAT" ) );
        svc.claims().claim( a, ready( "PLAT" ) );
        svc.claims().claim( a, ready( "OPS" ) );
        svc.removeMember( admin, "PLAT", "agent-a", "agent" );
        svc.claims().claim( b, ready( "PLAT" ) );
        svc.registry().retireAgent( admin, agentB.id() );
        final String expiring = ready( "OPS" );
        svc.claims().claim( a, expiring );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET claim_expires_at = now() - INTERVAL '1 minute' "
            + "WHERE claimed_by_agent IS NOT NULL AND id = (SELECT id FROM work_item WHERE project_id = "
            + "(SELECT id FROM work_project WHERE key = 'OPS') ORDER BY id DESC LIMIT 1)" );
        assertEquals( 1, svc.claims().sweepExpiredClaims() );
        svc.registry().retireAgent( admin, agentA.id() );

        assertEquals( 1, count( ClaimOutcome.REFUSED ), "a refusal has no event" );
        assertEquals( logged( WorkEvent.CLAIMED ), count( ClaimOutcome.CLAIMED ) );
        assertEquals( logged( WorkEvent.CLAIM_RENEWED ), count( ClaimOutcome.RENEWED ) );
        assertEquals( logged( WorkEvent.RELEASED ), count( ClaimOutcome.RELEASED ) );
        assertEquals( logged( WorkEvent.CLAIM_EXPIRED ), count( ClaimOutcome.EXPIRED ) );
        assertTrue( count( ClaimOutcome.CLAIMED ) >= 8 && count( ClaimOutcome.RENEWED ) == 1
            && count( ClaimOutcome.RELEASED ) >= 7 && count( ClaimOutcome.EXPIRED ) == 1, heard.toString() );
    }
}
