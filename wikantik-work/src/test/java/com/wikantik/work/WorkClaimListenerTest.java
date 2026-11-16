package com.wikantik.work;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The claim listener hears every ending the service causes, once each, and can never break a write. */
class WorkClaimListenerTest {

    private static final Instant NOW = Instant.parse( "2026-11-16T12:00:00Z" );

    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor admin = Actor.human( "root", Set.of(), true );
    private final Actor codex = Actor.agent( 7, "codex@docker2" );
    private final List< ClaimOutcome > outcomes = new ArrayList<>();
    private boolean listenerThrows;

    private WorkStore store;
    private WorkClaimStore claims;
    private WorkService service;

    private static Item item( final WorkStatus status, final Long holder ) {
        return new Item( 12, 1, "PLAT-1", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "title", "body", status, 3,
            1024, null, null, null, null, null, null, 4, NOW, NOW, null, "implementer", holder,
            holder == null ? null : NOW, holder == null ? null : NOW.plusSeconds( 3600 ), null );
    }

    @BeforeEach
    void setUp() throws SQLException {
        store = mock( WorkStore.class );
        claims = mock( WorkClaimStore.class );
        when( store.claims() ).thenReturn( claims );
        final Project plat = new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, NOW, null );
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( plat ) );
        when( store.roleFor( eq( 1L ), argThat( a -> a != null && "lena".equals( a.login() ) ) ) )
            .thenReturn( Optional.of( WorkRole.LEAD ) );
        when( store.queue( "implementer" ) ).thenReturn( Optional.of( new Queue( "implementer", "I", "", null ) ) );
        when( store.agentByName( "codex@docker2" ) ).thenReturn( Optional.of( agent( null ) ) );
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( null ) ) );
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return Optional.of( name.toLowerCase( java.util.Locale.ROOT ) );
            }
        }, AgentKeys.NONE, Clock.fixed( NOW, ZoneOffset.UTC ), WorkSettings.DEFAULTS, new WorkListener() {
            @Override public void claim( final ClaimOutcome o ) {
                outcomes.add( o );
                if ( listenerThrows ) {
                    throw new IllegalStateException( "listener is broken" );
                }
            }
        } );
    }

    private static Agent agent( final Instant retired ) {
        return new Agent( 7, "codex@docker2", "", "", 1, List.of( "implementer" ), NOW, retired );
    }

    private void stored( final Item i ) throws SQLException {
        when( store.item( i.key() ) ).thenReturn( Optional.of( i ) );
        when( store.itemById( i.id() ) ).thenReturn( Optional.of( i ) );
    }

    /** The store's answer to a change: the new item, with the claim cleared the way the real store does. */
    private void storeClearsTheClaim() throws SQLException {
        when( store.writeItem( any(), any(), any( Actor.class ), any(), org.mockito.ArgumentMatchers.anyBoolean() ) ).thenAnswer( inv ->
            new WorkStore.ItemWrite( cleared( inv.getArgument( 1 ) ), ( ( Item ) inv.getArgument( 0 ) ).claimedByAgent() ) );
    }

    private static Item cleared( final Item n ) {
        return new Item( n.id(), n.projectId(), n.key(), n.level(), n.parentId(), n.productId(), n.kind(), n.title(),
            n.body(), n.status(), n.priority(), n.rank(), n.dueOn(), n.assignee(), n.reporter(), n.autonomy(),
            n.doneMeans(), n.allowDirect(), n.version() + 1, n.createdAt(), n.updatedAt(), n.closedAt(), n.queue(),
            null, null, null, n.reporterAgentId() );
    }

    private WorkClaimsService.Handoff toLena() {
        return new WorkClaimsService.Handoff( 4, WorkStatus.REVIEW, "Lena", null, "over to you" );
    }

    @Test
    void handoffOfAClaimedItemReportsReleased() throws SQLException {
        stored( item( WorkStatus.IN_PROGRESS, 7L ) );
        storeClearsTheClaim();
        service.claims().handoff( lena, "PLAT-1", toLena() );
        assertEquals( List.of( ClaimOutcome.RELEASED ), outcomes );
    }

    @Test
    void handoffOfAnUnclaimedItemReportsNothing() throws SQLException {
        stored( item( WorkStatus.READY, null ) );
        storeClearsTheClaim();
        service.claims().handoff( lena, "PLAT-1", toLena() );
        assertTrue( outcomes.isEmpty() );
    }

    @Test
    void updateThatMovesAClaimedItemReportsReleased() throws SQLException {
        stored( item( WorkStatus.IN_PROGRESS, 7L ) );
        storeClearsTheClaim();
        service.updateItem( lena, "PLAT-1", new ItemPatch( 4, Map.of( "status", "ready" ), null ) );
        assertEquals( List.of( ClaimOutcome.RELEASED ), outcomes );
    }

    @Test
    void bodyEditOfAClaimedItemReportsNothing() throws SQLException {
        stored( item( WorkStatus.IN_PROGRESS, 7L ) );
        when( store.writeItem( any(), any(), any( Actor.class ), any(), org.mockito.ArgumentMatchers.anyBoolean() ) )
            .thenAnswer( inv -> new WorkStore.ItemWrite( inv.getArgument( 1 ), null ) );
        service.updateItem( lena, "PLAT-1", new ItemPatch( 4, Map.of( "body", "new" ), null ) );
        assertTrue( outcomes.isEmpty() );
    }

    @Test
    void removingAnAgentMemberReportsOneReleasedPerClaim() throws SQLException {
        when( store.removeMember( anyLong(), eq( "7" ), eq( "agent" ), any( Actor.class ) ) ).thenReturn( 2 );
        service.removeMember( admin, "PLAT", "codex@docker2", "agent" );
        assertEquals( List.of( ClaimOutcome.RELEASED, ClaimOutcome.RELEASED ), outcomes );
    }

    @Test
    void demotingAnAgentToViewerReportsItsReleases() throws SQLException {
        when( store.putMember( anyLong(), eq( "7" ), eq( "agent" ), eq( WorkRole.VIEWER ), any( Actor.class ) ) )
            .thenReturn( 1 );
        service.putMember( admin, "PLAT", "codex@docker2", "agent", WorkRole.VIEWER );
        assertEquals( List.of( ClaimOutcome.RELEASED ), outcomes );
    }

    @Test
    void retiringAnAgentReportsOneReleasedPerClaim() throws SQLException {
        when( claims.retireAgent( eq( 7L ), any(), any( Actor.class ) ) ).thenReturn( 3 );
        service.registry().retireAgent( admin, 7 );
        assertEquals( 3, outcomes.size() );
        assertTrue( outcomes.stream().allMatch( o -> o == ClaimOutcome.RELEASED ) );
    }

    @Test
    void retiringARetiredAgentReportsNothing() throws SQLException {
        when( store.agent( 7 ) ).thenReturn( Optional.of( agent( NOW ) ) );
        service.registry().retireAgent( admin, 7 );
        assertTrue( outcomes.isEmpty() );
    }

    @Test
    void failedStoreCallReportsNothing() throws SQLException {
        stored( item( WorkStatus.IN_PROGRESS, 7L ) );
        when( store.writeItem( any(), any(), any( Actor.class ), any(), org.mockito.ArgumentMatchers.anyBoolean() ) )
            .thenThrow( new WorkConflictException( "stale" ) );
        when( store.removeMember( anyLong(), any(), any(), any( Actor.class ) ) ).thenThrow( new SQLException( "down" ) );
        when( claims.retireAgent( anyLong(), any(), any( Actor.class ) ) ).thenThrow( new SQLException( "down" ) );
        assertThrows( WorkConflictException.class, () -> service.claims().handoff( lena, "PLAT-1", toLena() ) );
        assertThrows( WorkConflictException.class, () -> service.updateItem( lena, "PLAT-1",
            new ItemPatch( 4, Map.of( "status", "ready" ), null ) ) );
        assertThrows( SQLException.class, () -> service.removeMember( admin, "PLAT", "codex@docker2", "agent" ) );
        assertThrows( SQLException.class, () -> service.registry().retireAgent( admin, 7 ) );
        assertTrue( outcomes.isEmpty() );
    }

    @Test
    void throwingListenerDoesNotFailTheWrite() throws SQLException {
        listenerThrows = true;
        stored( item( WorkStatus.IN_PROGRESS, 7L ) );
        storeClearsTheClaim();
        when( store.removeMember( anyLong(), any(), any(), any( Actor.class ) ) ).thenReturn( 1 );
        when( claims.retireAgent( anyLong(), any(), any( Actor.class ) ) ).thenReturn( 1 );
        assertEquals( WorkStatus.REVIEW, service.claims().handoff( lena, "PLAT-1", toLena() ).item().status() );
        service.updateItem( lena, "PLAT-1", new ItemPatch( 4, Map.of( "status", "ready" ), null ) );
        service.removeMember( admin, "PLAT", "codex@docker2", "agent" );
        service.registry().retireAgent( admin, 7 );
        assertEquals( 4, outcomes.size(), "the listener was still called each time" );
    }
}
