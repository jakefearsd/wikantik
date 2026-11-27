package com.wikantik.auth.authorize;

import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Session;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.SecurityEventTrap;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DefaultGroupManager: who created / last changed a group survives an edit, removal reaches the back-end,
 * and a profile rename re-saves exactly the groups it touches.
 */
class DefaultGroupManagerPreservationTest {

    private static final String G = "PreservationGroup";
    private static final String OTHER = "PreservationOther";

    private TestEngine engine;
    private Properties props;
    private GroupManager groups;
    private Session admin;
    private final SecurityEventTrap trap = new SecurityEventTrap();

    @BeforeEach
    void setUp() throws Exception {
        props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        groups = engine.getManager( GroupManager.class );
        admin = WikiSessionTest.adminSession( engine );
        removeQuietly( G );
        removeQuietly( OTHER );
    }

    @AfterEach
    void tearDown() throws Exception {
        removeQuietly( G );
        removeQuietly( OTHER );
        engine.stop();
    }

    private void removeQuietly( final String name ) throws Exception {
        try {
            groups.removeGroup( name );
        } catch ( final NoSuchPrincipalException e ) {
            assertEquals( "Group " + name + " not found", e.getMessage() );
        }
    }

    private Group create( final String name, final String members ) throws Exception {
        groups.setGroup( admin, groups.parseGroup( name, members, true ) );
        return groups.getGroup( name );
    }

    @Test
    void editingAGroupKeepsItsCreatorAndCreationDate() throws Exception {
        final Group original = create( G, "Alice" );
        assertNotNull( original.getCreator() );
        assertNotNull( original.getCreated() );
        final String creator = original.getCreator();
        final java.util.Date created = original.getCreated();

        // an edit that arrives as a freshly built Group of the same name (what the edit form does)
        final Group replacement = new Group( G, engine.getApplicationName() );
        replacement.add( new WikiPrincipal( "Bob" ) );
        groups.setGroup( admin, replacement );

        final Group after = groups.getGroup( G );
        assertEquals( creator, after.getCreator() );
        assertEquals( created, after.getCreated() );
        assertTrue( after.isMember( new WikiPrincipal( "Bob" ) ) );
        assertFalse( after.isMember( new WikiPrincipal( "Alice" ) ) );
    }

    @Test
    void parseGroupOfAnExistingGroupCarriesItsAuditFields() throws Exception {
        final Group original = create( G, "Alice" );
        final Group parsed = groups.parseGroup( G, "", false );
        assertAll(
            () -> assertEquals( original.getCreator(), parsed.getCreator() ),
            () -> assertEquals( original.getCreated(), parsed.getCreated() ),
            () -> assertEquals( original.getModifier(), parsed.getModifier() ),
            () -> assertEquals( original.getLastModified(), parsed.getLastModified() ),
            () -> assertNotNull( parsed.getCreator() ),
            () -> assertNotNull( parsed.getCreated() ) );
    }

    @Test
    void parseGroupWithAMemberLineReplacesButKeepsAuditFields() throws Exception {
        final Group original = create( G, "Alice\nBob" );
        final Group parsed = groups.parseGroup( G, "Carol", false );
        assertEquals( original.getCreator(), parsed.getCreator() );
        assertEquals( 1, parsed.members().length );
        assertTrue( parsed.isMember( new WikiPrincipal( "Carol" ) ) );
    }

    @Test
    void theModifierIsTheSavingUsersLogin() throws Exception {
        final Group g = create( G, "Alice" );
        assertEquals( "admin", g.getModifier() );
        assertEquals( "admin", g.getCreator() );
    }

    @Test
    void anAnonymousSaverIsRecordedByTheirSessionPrincipal() throws Exception {
        final Session anon = WikiSessionTest.anonymousSession( engine );
        groups.setGroup( anon, groups.parseGroup( G, "Alice", true ) );
        final String modifier = groups.getGroup( G ).getModifier();
        assertNotNull( modifier );
        assertFalse( modifier.isBlank() );
    }

    private Group[] backEnd() throws Exception {
        return ( (DefaultGroupManager) groups ).getGroupDatabase().groups();
    }

    private boolean backEndHas( final String name ) throws Exception {
        return Arrays.stream( backEnd() ).anyMatch( g -> g.getName().equals( name ) );
    }

    @Test
    void aRemovedGroupIsGoneFromTheBackEndNotJustTheCache() throws Exception {
        create( G, "Alice" );
        assertTrue( backEndHas( G ) );
        groups.removeGroup( G );
        assertThrows( NoSuchPrincipalException.class, () -> groups.getGroup( G ) );
        assertFalse( backEndHas( G ) );
    }

    @Test
    void removingAGroupAnnouncesGroupRemove() throws Exception {
        create( G, "Alice" );
        groups.addWikiEventListener( trap );
        trap.clearEvents();
        groups.removeGroup( G );
        assertEquals( 1, trap.events().length );
        assertEquals( WikiSecurityEvent.GROUP_REMOVE, trap.events()[ 0 ].getType() );
        groups.removeWikiEventListener( trap );
    }

    @Test
    void removingAnUnknownGroupIsRefusedAndNullIsIllegal() {
        assertThrows( NoSuchPrincipalException.class, () -> groups.removeGroup( "NoSuchGroupAnywhere" ) );
        assertThrows( IllegalArgumentException.class, () -> groups.removeGroup( null ) );
    }

    @Test
    void initializeLoadsEveryBackEndGroupAndAnnouncesEachOne() throws Exception {
        create( G, "Alice" );
        final DefaultGroupManager fresh = new DefaultGroupManager();
        fresh.addWikiEventListener( trap );
        fresh.initialize( engine, props );
        // the in-memory test back-end is per-manager, so the fresh manager holds its own seeded groups
        final int loaded = fresh.getRoles().length;
        assertTrue( loaded >= 1 );
        final long adds = Arrays.stream( trap.events() ).filter( e -> e.getType() == WikiSecurityEvent.GROUP_ADD ).count();
        assertEquals( loaded, adds );
        fresh.removeWikiEventListener( trap );
    }

    @Test
    void replacingAGroupAnnouncesRemoveThenAddAndNewGroupAnnouncesOnlyAdd() throws Exception {
        groups.addWikiEventListener( trap );
        create( G, "Alice" );
        assertEquals( 1, trap.events().length );
        assertEquals( WikiSecurityEvent.GROUP_ADD, trap.events()[ 0 ].getType() );
        trap.clearEvents();
        groups.setGroup( admin, groups.parseGroup( G, "Bob", false ) );
        final WikiSecurityEvent[] events = trap.events();
        assertEquals( 2, events.length );
        assertEquals( WikiSecurityEvent.GROUP_REMOVE, events[ 0 ].getType() );
        assertEquals( WikiSecurityEvent.GROUP_ADD, events[ 1 ].getType() );
        groups.removeWikiEventListener( trap );
    }

    @Test
    void aProfileRenameResavesOnlyTheGroupsItTouches() throws Exception {
        create( G, "oldlogin" );
        create( OTHER, "somebodyelse" );
        groups.addWikiEventListener( trap );
        trap.clearEvents();

        final UserProfile oldProfile = mock( UserProfile.class );
        when( oldProfile.getLoginName() ).thenReturn( "oldlogin" );
        when( oldProfile.getFullname() ).thenReturn( "Old Full" );
        when( oldProfile.getWikiName() ).thenReturn( "OldFull" );
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getLoginName() ).thenReturn( "newlogin" );
        final WikiSecurityEvent event = mock( WikiSecurityEvent.class );
        when( event.getType() ).thenReturn( WikiSecurityEvent.PROFILE_NAME_CHANGED );
        when( event.getSrc() ).thenReturn( admin );
        when( event.getTarget() ).thenReturn( new UserProfile[] { oldProfile, newProfile } );

        groups.actionPerformed( event );

        final Group touched = groups.getGroup( G );
        assertTrue( touched.isMember( new WikiPrincipal( "newlogin" ) ) );
        assertFalse( touched.isMember( new WikiPrincipal( "oldlogin" ) ) );
        // exactly one group was re-saved: one REMOVE + one ADD, none for the untouched group
        final WikiSecurityEvent[] events = trap.events();
        assertEquals( 2, events.length, Arrays.toString( events ) );
        assertTrue( groups.getGroup( OTHER ).isMember( new WikiPrincipal( "somebodyelse" ) ) );

        // and it reached the back-end
        final Group stored = Arrays.stream( backEnd() ).filter( g -> g.getName().equals( G ) ).findFirst().orElseThrow();
        assertTrue( stored.isMember( new WikiPrincipal( "newlogin" ) ) );
        assertFalse( stored.isMember( new WikiPrincipal( "oldlogin" ) ) );
        groups.removeWikiEventListener( trap );
    }

    @Test
    void anUnrelatedEventIsIgnored() throws Exception {
        create( G, "Alice" );
        final WikiSecurityEvent event = mock( WikiSecurityEvent.class );
        when( event.getType() ).thenReturn( WikiSecurityEvent.LOGIN_AUTHENTICATED );
        groups.actionPerformed( event );
        assertTrue( groups.getGroup( G ).isMember( new WikiPrincipal( "Alice" ) ) );
    }
}
