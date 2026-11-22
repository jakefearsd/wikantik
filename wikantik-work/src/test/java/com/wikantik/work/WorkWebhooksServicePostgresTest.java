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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class WorkWebhooksServicePostgresTest {

    static final String URL = "https://hooks.example.com/services/T0/B0/XYZSECRET";

    private static final Actor ROOT = NotifyServiceFixture.ROOT;
    private static final Actor LENA = NotifyServiceFixture.LENA;
    private static final Actor OTTO = NotifyServiceFixture.OTTO;
    private static final Actor MALLORY = NotifyServiceFixture.MALLORY;

    private RecordingOutbound outbound;
    private NotifyServiceFixture fx;
    private WorkWebhooksService hooks;

    static WorkNotifyPorts ports( final RecordingOutbound outbound ) {
        return new WorkNotifyPorts( WorkMailer.NONE, outbound, new NotifySettings( Duration.ofSeconds( 60 ), false,
            EmailDetail.SUMMARY, Duration.ofMinutes( 5 ), 8, "https://wiki.example.com", true ) );
    }

    @BeforeEach
    void setUp() throws SQLException {
        outbound = new RecordingOutbound();
        fx = new NotifyServiceFixture( ports( outbound ) );
        hooks = fx.service.features().webhooks();
    }

    private static WebhookDraft draft( final String url ) {
        return new WebhookDraft( url, "generic", List.of() );
    }

    @Test
    void aLeadAddsAndSeesTheSecretOnce() throws SQLException {
        final WebhookCreated made = hooks.add( LENA, "WIKI", new WebhookDraft( URL, "generic", List.of( "status", "created" ) ) );
        assertTrue( made.secret().matches( "[0-9a-f]{64}" ) );
        assertEquals( "https://hooks.example.com/…", made.webhook().target() );
        assertEquals( "generic", made.webhook().format() );
        assertEquals( "lena", made.webhook().ownerName() );
        assertFalse( made.webhook().paused() );
        assertEquals( 1, outbound.checked.size(), "the address is checked at save" );
        final List< WebhookView > listed = hooks.list( LENA, "WIKI" );
        final WebhookView updated = hooks.update( LENA, "WIKI", made.webhook().id(),
            new WebhookPatch( null, List.of( "status" ), null ) );
        for ( final Object shown : new Object[] { listed, updated } ) {
            assertFalse( shown.toString().contains( made.secret() ) || shown.toString().contains( "XYZSECRET" ), shown.toString() );
        }
        assertEquals( 1, listed.size() );
        assertEquals( List.of( "status" ), updated.eventTypes() );
        assertTrue( fx.store.notifyStores().webhooks().byId( made.webhook().id() ).orElseThrow().secret()
            .equals( made.secret() ), "stored as issued" );
    }

    @Test
    void slackHasNoSecret() throws SQLException {
        final WebhookCreated made = hooks.add( LENA, "WIKI", new WebhookDraft( URL, "slack", null ) );
        assertEquals( null, made.secret() );
        assertEquals( "slack", made.webhook().format() );
    }

    @Test
    void aPrivateAddressIsRefusedAtAdd() {
        for ( final String url : new String[] { "https://10.0.0.5/x", "https://127.0.0.1/x" } ) {
            final WorkValidationException e = assertThrows( WorkValidationException.class, () -> hooks.add( LENA, "WIKI", draft( url ) ) );
            assertEquals( "url", e.field() );
        }
        assertThrows( WorkValidationException.class, () -> hooks.update( LENA, "WIKI", addOne(), new WebhookPatch( "https://10.0.0.5/y", null, null ) ) );
    }

    private long addOne() throws SQLException {
        return hooks.add( LENA, "WIKI", draft( URL ) ).webhook().id();
    }

    @Test
    void httpAndUserInfoAreRefused() {
        for ( final String url : new String[] { "http://hooks.example.com/x", "https://bob:pw@hooks.example.com/x",
            "hooks.example.com/x", "", "https://", "ftp://hooks.example.com/", "https://hooks.example.com/" + "a".repeat( 2000 ) } ) {
            final WorkValidationException e = assertThrows( WorkValidationException.class, () -> hooks.add( LENA, "WIKI", draft( url ) ), url );
            assertEquals( "url", e.field() );
            assertEquals( "Give an https address with no user name or password in it.", e.getMessage() );
        }
        assertTrue( outbound.checked.isEmpty(), "a malformed address never reaches the egress check" );
    }

    @Test
    void anUnknownEventTypeIsRefused() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> hooks.add( LENA, "WIKI", new WebhookDraft( URL, "generic", List.of( "status", "sprint_started" ) ) ) );
        assertEquals( "eventTypes", e.field() );
        assertEquals( "sprint_started is not an event a webhook can send.", e.getMessage() );
        assertThrows( WorkValidationException.class,
            () -> hooks.add( LENA, "WIKI", new WebhookDraft( URL, "generic", List.of( "comment_edited" ) ) ) );
        assertThrows( WorkValidationException.class,
            () -> hooks.add( LENA, "WIKI", new WebhookDraft( URL, "teams", List.of() ) ) );
    }

    @Test
    void anEleventhWebhookIsRefused() throws SQLException {
        for ( int i = 0; i < 10; i++ ) {
            hooks.add( LENA, "WIKI", draft( "https://h" + i + ".example.com/x" ) );
        }
        final WorkValidationException e = assertThrows( WorkValidationException.class, () -> hooks.add( LENA, "WIKI", draft( URL ) ) );
        assertEquals( "A project has at most 10 webhooks.", e.getMessage() );
    }

    @Test
    void aMemberCannotAddAndListsNothing() throws SQLException {
        addOne();
        assertThrows( WorkDeniedException.class, () -> hooks.add( OTTO, "WIKI", draft( URL ) ) );
        assertThrows( WorkDeniedException.class, () -> hooks.update( OTTO, "WIKI", 1, new WebhookPatch( null, null, false ) ) );
        assertThrows( WorkDeniedException.class, () -> hooks.remove( OTTO, "WIKI", 1 ) );
        assertEquals( List.of(), hooks.list( OTTO, "WIKI" ) );
    }

    @Test
    void anAgentCannotAdd() throws SQLException {
        final long id = WorkAgentFixtures.agent( fx.ds, "bot@x" );
        fx.store.putMember( fx.project.id(), String.valueOf( id ), "agent", WorkRole.MEMBER, ROOT );
        final Actor agent = Actor.agent( id, "bot@x", LENA );
        assertThrows( WorkDeniedException.class, () -> hooks.add( agent, "WIKI", draft( URL ) ) );
        assertEquals( List.of(), hooks.list( agent, "WIKI" ) );
    }

    @Test
    void aStrangerGetsNotFound() throws SQLException {
        final long id = addOne();
        assertThrows( WorkNotFoundException.class, () -> hooks.list( MALLORY, "WIKI" ) );
        assertThrows( WorkNotFoundException.class, () -> hooks.add( MALLORY, "WIKI", draft( URL ) ) );
        assertThrows( WorkNotFoundException.class, () -> hooks.update( MALLORY, "WIKI", id, new WebhookPatch( null, null, false ) ) );
        assertThrows( WorkNotFoundException.class, () -> hooks.remove( MALLORY, "WIKI", id ) );
        assertThrows( WorkNotFoundException.class, () -> hooks.list( LENA, "NOPE" ) );
    }

    @Test
    void anotherProjectsIdIsNotFound() throws SQLException {
        final long id = addOne();
        final Project other = fx.store.createProject( "OTHER", "Other", "" );
        fx.store.putMember( other.id(), "lena", "user", WorkRole.LEAD, ROOT );
        assertThrows( WorkNotFoundException.class, () -> hooks.update( LENA, "OTHER", id, new WebhookPatch( null, null, false ) ) );
        assertThrows( WorkNotFoundException.class, () -> hooks.remove( LENA, "OTHER", id ) );
        assertThrows( WorkNotFoundException.class, () -> hooks.remove( LENA, "WIKI", 999_999 ) );
        assertEquals( 1, hooks.list( LENA, "WIKI" ).size() );
    }

    @Test
    void anArchivedProjectIsArchived() throws SQLException {
        final long id = addOne();
        fx.service.archiveProject( ROOT, "WIKI" );
        for ( final org.junit.jupiter.api.function.Executable op : List.< org.junit.jupiter.api.function.Executable >of(
            () -> hooks.add( LENA, "WIKI", draft( URL ) ), () -> hooks.update( LENA, "WIKI", id, new WebhookPatch( null, null, false ) ),
            () -> hooks.remove( LENA, "WIKI", id ) ) ) {
            assertEquals( WorkConflictException.ARCHIVED, assertThrows( WorkConflictException.class, op ).reason() );
        }
        assertEquals( 1, hooks.list( LENA, "WIKI" ).size(), "reading still works" );
    }

    @Test
    void webhooksOffIsRefused() throws SQLException {
        final NotifyServiceFixture off = new NotifyServiceFixture();
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> off.service.features().webhooks().add( LENA, "WIKI", draft( URL ) ) );
        assertEquals( WorkConflictException.WEBHOOKS_OFF, e.reason() );
        assertEquals( "Webhooks are turned off on this wiki.", e.getMessage() );
        assertEquals( 0, off.service.features().webhooks().deliver( NotifyServiceFixture.NOW ) );
    }

    @Test
    void eachChangeIsAuditedWithTheHostOnly() throws SQLException {
        final long id = addOne();
        hooks.update( LENA, "WIKI", id, new WebhookPatch( "https://two.example.com/p/SECRETPATH", null, null ) );
        hooks.remove( LENA, "WIKI", id );
        assertEquals( List.of( "lena work.webhook.add work_webhook WIKI:hooks.example.com",
            "lena work.webhook.update work_webhook WIKI:two.example.com",
            "lena work.webhook.remove work_webhook WIKI:two.example.com" ), new ArrayList<>( fx.audited ) );
    }

    @Test
    void aPausedWebhookSaysSo() throws SQLException {
        final long id = addOne();
        fx.store.putMember( fx.project.id(), "otto", "user", WorkRole.LEAD, ROOT );
        fx.store.putMember( fx.project.id(), "lena", "user", WorkRole.MEMBER, ROOT );
        assertTrue( hooks.list( OTTO, "WIKI" ).get( 0 ).paused(), "its owner is no longer a lead" );
        assertFalse( hooks.update( OTTO, "WIKI", id, new WebhookPatch( null, null, null ) ).paused(), "saved by a lead: resumed" );
    }
}
