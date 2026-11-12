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
package com.wikantik.auth;

import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The private-cluster rule itself: which pages a configured private cluster hides, read from the page's stored
 * frontmatter and cached per page version.
 */
class PrivateClusterGateTest {

    private static final String HOME_LAB = "home-lab-infrastructure";
    private static final String REMOTE = "remote-host-management";

    private PageManager pages;
    private PrivateClusterGate gate;
    private final List< String > warns = new ArrayList<>();
    private AbstractAppender appender;

    @BeforeEach
    void setUp() {
        pages = mock( PageManager.class );
        gate = new PrivateClusterGate( Set.of( HOME_LAB, REMOTE ), Set.of( "HomeLab" ), pages );
        captureWarnings();
    }

    @AfterEach
    void releaseLog() {
        final org.apache.logging.log4j.core.LoggerContext ctx =
                ( org.apache.logging.log4j.core.LoggerContext ) LogManager.getContext( false );
        ctx.getConfiguration().removeLogger( PrivateClusterGate.class.getName() );
        ctx.updateLoggers();
        appender.stop();
    }

    private void captureWarnings() {
        appender = new AbstractAppender( "privateClusterGateTest", null, null, true, Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                if ( e.getLevel().isMoreSpecificThan( org.apache.logging.log4j.Level.WARN ) ) {
                    warns.add( e.getMessage().getFormattedMessage() );
                }
            }
        };
        appender.start();
        final org.apache.logging.log4j.core.LoggerContext ctx =
                ( org.apache.logging.log4j.core.LoggerContext ) LogManager.getContext( false );
        final org.apache.logging.log4j.core.config.Configuration config = ctx.getConfiguration();
        final org.apache.logging.log4j.core.config.LoggerConfig lc =
                org.apache.logging.log4j.core.config.LoggerConfig.newBuilder().setAdditivity( false )
                        .setLevel( org.apache.logging.log4j.Level.WARN ).setLoggerName( PrivateClusterGate.class.getName() )
                        .setIncludeLocation( "false" ).setRefs( new org.apache.logging.log4j.core.config.AppenderRef[ 0 ] )
                        .setConfig( config ).build();
        lc.addAppender( appender, org.apache.logging.log4j.Level.WARN, null );
        config.addLogger( PrivateClusterGate.class.getName(), lc );
        ctx.updateLoggers();
    }

    private Page page( final String name, final int version, final long lastModified, final String text ) {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( name );
        when( page.getVersion() ).thenReturn( version );
        when( page.getLastModified() ).thenReturn( new Date( lastModified ) );
        when( pages.getPureText( name, version ) ).thenReturn( text );
        return page;
    }

    private Page page( final String name, final String text ) {
        return page( name, 1, 1_000L, text );
    }

    @Test
    void aScalarClusterNamingAPrivateClusterIsPrivate() {
        assertTrue( gate.isPrivate( page( "Router", "---\ncluster: home-lab-infrastructure\n---\nBody.\n" ) ) );
    }

    @Test
    void aListWithThePrivateClusterSecondIsPrivate() {
        assertTrue( gate.isPrivate( page( "Router",
                "---\ncluster:\n  - wikantik-development\n  - remote-host-management\n---\nBody.\n" ) ) );
    }

    @Test
    void aSubClusterOfAPrivateClusterIsPrivate() {
        assertTrue( gate.isPrivate( page( "Nas", "---\ncluster: home-lab-infrastructure/storage\n---\nBody.\n" ) ) );
    }

    @Test
    void aClusterThatOnlySharesAPrefixIsNotPrivate() {
        assertFalse( gate.isPrivate( page( "Notes", "---\ncluster: home-lab-infrastructure-notes\n---\nBody.\n" ) ) );
    }

    @Test
    void theHubPageOfAPrivateClusterIsPrivate() {
        assertTrue( gate.isPrivate( page( "HomeLabHub",
                "---\ntype: hub\ncluster: home-lab-infrastructure\ntitle: Home lab\n---\nThe hub.\n" ) ) );
    }

    @Test
    void aPublicPageThatOnlyLinksToPrivatePagesIsNotPrivate() {
        assertFalse( gate.isPrivate( page( "Main",
                "---\ncluster: wikantik-development\n---\nSee [Router](Router) and home-lab-infrastructure.\n" ) ) );
    }

    @Test
    void aPageWithNoClusterIsNotPrivate() {
        assertFalse( gate.isPrivate( page( "Plain", "---\ntitle: Plain\n---\nBody.\n" ) ) );
        assertFalse( gate.isPrivate( page( "NoFrontmatter", "Just a body, cluster: home-lab-infrastructure\n" ) ) );
    }

    @Test
    void aPageWhoseTextCannotBeReadIsNotPrivate() {
        assertFalse( gate.isPrivate( page( "Gone", null ) ) );
        assertFalse( gate.isPrivate( page( "Empty", "" ) ) );
    }

    @Test
    void aPageWithBrokenFrontmatterIsNotPrivate() {
        assertFalse( gate.isPrivate( page( "Broken",
                "---\ncluster: home-lab-infrastructure\ntitle: [unclosed\n---\nBody.\n" ) ) );
    }

    @Test
    void anAttachmentFollowsItsParentPage() {
        final Page parent = page( "Router", 3, 5_000L, "---\ncluster: home-lab-infrastructure\n---\nBody.\n" );
        when( pages.getPageWithoutMetadata( "Router", PageProvider.LATEST_VERSION ) ).thenReturn( parent );
        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "Router/diagram.png" );
        when( att.getParentName() ).thenReturn( "Router" );

        assertTrue( gate.isPrivate( att ) );
    }

    @Test
    void anAttachmentOfAPublicPageIsNotPrivate() {
        final Page parent = page( "Public", 1, 5_000L, "---\ncluster: wikantik-development\n---\nBody.\n" );
        when( pages.getPageWithoutMetadata( "Public", PageProvider.LATEST_VERSION ) ).thenReturn( parent );
        final Attachment att = mock( Attachment.class );
        when( att.getName() ).thenReturn( "Public/diagram.png" );
        when( att.getParentName() ).thenReturn( "Public" );

        assertFalse( gate.isPrivate( att ) );
    }

    @Test
    void aSecondCheckOfTheSameVersionDoesNotReadTheTextAgain() {
        final Page v1 = page( "Router", 1, 1_000L, "---\ncluster: home-lab-infrastructure\n---\nBody.\n" );

        assertTrue( gate.isPrivate( v1 ) );
        assertTrue( gate.isPrivate( v1 ) );

        verify( pages, times( 1 ) ).getPureText( "Router", 1 );
    }

    @Test
    void aNewVersionIsReadAgain() {
        final Page v1 = page( "Router", 1, 1_000L, "---\ncluster: home-lab-infrastructure\n---\nBody.\n" );
        assertTrue( gate.isPrivate( v1 ) );

        final Page v2 = page( "Router", 2, 2_000L, "---\ncluster: wikantik-development\n---\nMoved out.\n" );
        assertFalse( gate.isPrivate( v2 ), "the version bump is seen, not the cached answer" );

        verify( pages, times( 1 ) ).getPureText( "Router", 1 );
        verify( pages, times( 1 ) ).getPureText( "Router", 2 );
    }

    @Test
    void aChangedLastModifiedAtTheSameVersionIsReadAgain() {
        final Page first = page( "Router", 1, 1_000L, "---\ncluster: wikantik-development\n---\nBody.\n" );
        assertFalse( gate.isPrivate( first ) );

        // A non-versioning provider rewrites in place: same version number, new timestamp.
        final Page rewritten = page( "Router", 1, 9_000L, "---\ncluster: home-lab-infrastructure\n---\nBody.\n" );
        assertTrue( gate.isPrivate( rewritten ) );
    }

    @Test
    void anUnreadableTextIsNotCachedSoTheNextCheckReadsAgain() {
        final Page page = page( "Flaky", 1, 1_000L, "" );
        assertFalse( gate.isPrivate( page ) );
        when( pages.getPureText( "Flaky", 1 ) ).thenReturn( "---\ncluster: home-lab-infrastructure\n---\nBody.\n" );

        assertTrue( gate.isPrivate( page ), "an empty read (a swallowed provider error) must not stick" );
    }

    @Test
    void anUnreadableTextIsLoggedOncePerPageVersion() {
        final Page v1 = page( "Flaky", 1, 1_000L, null );
        assertFalse( gate.isPrivate( v1 ) );
        assertFalse( gate.isPrivate( v1 ) );
        assertEquals( 1, warns.stream().filter( w -> w.contains( "Flaky" ) ).count(), String.valueOf( warns ) );

        final Page v2 = page( "Flaky", 2, 2_000L, "" );
        assertFalse( gate.isPrivate( v2 ) );
        assertEquals( 2, warns.stream().filter( w -> w.contains( "Flaky" ) ).count(), "a new version is logged again" );
    }

    @Test
    void aTextOnItsOwnIsJudgedByTheSameRule() {
        assertTrue( gate.isPrivateText( "---\ncluster: home-lab-infrastructure/lan\n---\nBody.\n" ) );
        assertTrue( gate.isPrivateText( "---\ncluster: [notes, remote-host-management]\n---\nBody.\n" ) );
        assertFalse( gate.isPrivateText( "---\ncluster: home-lab-infrastructure-notes\n---\nBody.\n" ) );
        assertFalse( gate.isPrivateText( "no frontmatter" ) );
        assertFalse( gate.isPrivateText( null ) );
        assertFalse( gate.isPrivateText( "---\ncluster: home-lab-infrastructure\ntitle: [unclosed\n---\nBody.\n" ) );
        assertFalse( new PrivateClusterGate( Set.of(), Set.of(), pages )
                .isPrivateText( "---\ncluster: home-lab-infrastructure\n---\nBody.\n" ), "a disabled gate" );
    }

    @Test
    void aDisabledGateDoesNoWork() {
        final PrivateClusterGate off = new PrivateClusterGate( Set.of(), Set.of(), pages );
        final Page page = mock( Page.class );

        assertFalse( off.enabled() );
        assertFalse( off.isPrivate( page ) );
        verifyNoInteractions( pages );
        verifyNoInteractions( page );
    }

    @Test
    void fromPropertiesReadsBothCommaSeparatedSettings() {
        final Properties props = new Properties();
        props.setProperty( PrivateClusterGate.PROP_PRIVATE_CLUSTERS, " home-lab-infrastructure , remote-host-management,, " );
        props.setProperty( PrivateClusterGate.PROP_VIEWERS, "HomeLab, Operators ," );

        final PrivateClusterGate fromProps = PrivateClusterGate.fromProperties( props, pages );

        assertTrue( fromProps.enabled() );
        assertEquals( Set.of( "HomeLab", "Operators" ), fromProps.viewerGroups() );
        assertTrue( fromProps.isPrivate( page( "A", "---\ncluster: remote-host-management\n---\nx\n" ) ) );
    }

    @Test
    void blankSettingsLeaveTheGateDisabled() {
        final Properties props = new Properties();
        props.setProperty( PrivateClusterGate.PROP_PRIVATE_CLUSTERS, "  " );
        props.setProperty( PrivateClusterGate.PROP_VIEWERS, "HomeLab" );

        assertFalse( PrivateClusterGate.fromProperties( props, pages ).enabled() );
        assertFalse( PrivateClusterGate.fromProperties( new Properties(), pages ).enabled() );
        verify( pages, never() ).getPureText( anyString(), anyInt() );
    }

    @Test
    void aSystemPropertyOverridesTheLoadedSettings() {
        // The engine's Properties never carry -D values (PropertyReader keys them wrongly), so every setting
        // must be read through TextUtil, which consults system properties first, as the other keys do.
        final Properties props = new Properties();
        System.setProperty( PrivateClusterGate.PROP_PRIVATE_CLUSTERS, "it-private" );
        System.setProperty( PrivateClusterGate.PROP_VIEWERS, "Operators" );
        try {
            final PrivateClusterGate fromSystem = PrivateClusterGate.fromProperties( props, pages );

            assertTrue( fromSystem.enabled(), "-Dwikantik.access.privateClusters must enable the gate" );
            assertEquals( Set.of( "Operators" ), fromSystem.viewerGroups() );
        } finally {
            System.clearProperty( PrivateClusterGate.PROP_PRIVATE_CLUSTERS );
            System.clearProperty( PrivateClusterGate.PROP_VIEWERS );
        }
    }
}
