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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookPayloadsTest {

    private static final Function< String, String > NAMES = login -> switch ( login ) {
        case "otto" -> "Otto Member";
        case "lena" -> "Lena Lead";
        default -> "Someone";
    };
    private static final Instant AT = Instant.parse( "2026-03-02T10:00:00Z" );

    private static WebhookEvent event( final String type, final String from, final String to, final String note,
                                       final String title ) {
        return new WebhookEvent( new WorkEvent( 42, 1L, 7L, "WIKI-7", AT, "lena", Actor.Kind.HUMAN, null, type, from,
            to, note ), "900", "WIKI", title, WorkStatus.READY, "feature" );
    }

    @Test
    void theSignatureMatchesAKnownVector() {
        assertEquals( "sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            WebhookPayloads.signature( "Jefe", "what do ya want for nothing?" ) );
    }

    @Test
    void genericCarriesNoNoteNoLoginAndNoText() {
        final String json = WebhookPayloads.generic( event( WorkEvent.ASSIGNED, null, "otto", "private", "A title" ),
            "https://wiki.example.com", NAMES );
        assertFalse( json.contains( "otto" ), json );
        assertFalse( json.contains( "lena" ), json );
        assertFalse( json.contains( "private" ), json );
        assertTrue( json.contains( "Otto Member" ), json );
        assertTrue( json.contains( "Lena Lead" ), json );
    }

    @Test
    void theGenericBodyHasTheFixedShape() {
        final String json = WebhookPayloads.generic( event( WorkEvent.EDITED, null, null, null, "A title" ),
            "https://wiki.example.com", NAMES );
        assertEquals( "{\"event\":{\"id\":42,\"type\":\"edited\",\"at\":\"2026-03-02T10:00:00Z\",\"actor\":\"Lena Lead\","
            + "\"text\":\"Lena Lead updated this\"},\"item\":{\"key\":\"WIKI-7\",\"title\":\"A title\","
            + "\"status\":\"ready\",\"url\":\"https://wiki.example.com/work/item/WIKI-7\",\"type\":\"feature\"},"
            + "\"project\":{\"key\":\"WIKI\"}}", json );
    }

    @Test
    void statusCarriesFromAndTo() {
        final JsonObject e = JsonParser.parseString( WebhookPayloads.generic(
            event( WorkEvent.STATUS, "ready", "in_progress", null, "t" ), "", NAMES ) ).getAsJsonObject()
            .getAsJsonObject( "event" );
        assertEquals( "ready", e.get( "from" ).getAsString() );
        assertEquals( "in_progress", e.get( "to" ).getAsString() );
        final JsonObject other = JsonParser.parseString( WebhookPayloads.generic(
            event( WorkEvent.FIELD, "a", "b", "estimate", "t" ), "", NAMES ) ).getAsJsonObject().getAsJsonObject( "event" );
        assertFalse( other.has( "from" ) || other.has( "to" ), "only status carries from and to" );
        assertEquals( "Lena Lead updated this", other.get( "text" ).getAsString() );
    }

    @Test
    void anAgentIsNamedByItsName() {
        final WebhookEvent w = new WebhookEvent( new WorkEvent( 5, 1L, 7L, "WIKI-7", AT, "codex@docker2",
            Actor.Kind.AGENT, 3L, WorkEvent.CLAIM_EXPIRED, null, null, null ), "9", "WIKI", "t", WorkStatus.READY, "task" );
        assertTrue( WebhookPayloads.generic( w, "", NAMES ).contains( "\"actor\":\"codex@docker2\"" ) );
    }

    @Test
    void slackEscapesMentionsAndAmpersands() {
        final String json = WebhookPayloads.slack( event( WorkEvent.CREATED, null, null, null, "<!channel> & co" ),
            "https://wiki.example.com", NAMES );
        assertEquals( "{\"text\":\"<https://wiki.example.com/work/item/WIKI-7|WIKI-7> &lt;!channel&gt; &amp; co: "
            + "Lena Lead updated this\"}", json );
    }

    @Test
    void aBlankBaseGivesAPath() {
        assertTrue( WebhookPayloads.generic( event( WorkEvent.EDITED, null, null, null, "t" ), "", NAMES )
            .contains( "\"url\":\"/work/item/WIKI-7\"" ) );
        assertTrue( WebhookPayloads.slack( event( WorkEvent.EDITED, null, null, null, "t" ), "", NAMES )
            .startsWith( "{\"text\":\"WIKI-7 t: " ) );
    }

    @Test
    void newSecretsDiffer() {
        final String a = WebhookPayloads.newSecret();
        assertNotEquals( a, WebhookPayloads.newSecret() );
        assertEquals( 64, a.length() );
        assertTrue( a.matches( "[0-9a-f]{64}" ) );
    }

    @Test
    void noSendableTextAddressesTheReaderAsYou() {
        for ( final String type : WebhookPayloads.SENDABLE ) {
            final String text = NoticeText.line( event( type, "ready", "otto", null, "t" ).event(), null, null, NAMES );
            assertFalse( text.contains( " you" ), type + ": " + text );
        }
        assertFalse( WebhookPayloads.SENDABLE.contains( WorkEvent.COMMENT_EDITED ), "its text says \"you\"" );
    }

    @Test
    void sprintAndProjectEventsAreNotSendable() {
        for ( final String type : new String[] { WorkEvent.SPRINT_CREATED, WorkEvent.SPRINT_UPDATED,
            WorkEvent.SPRINT_STARTED, WorkEvent.SPRINT_CLOSED, WorkEvent.SETTINGS, WorkEvent.MEMBER } ) {
            assertFalse( WebhookPayloads.SENDABLE.contains( type ), type );
        }
    }

    @Test
    void attachmentEventsAreNotSendable() {
        assertFalse( WebhookPayloads.SENDABLE.contains( WorkEvent.ATTACHED ) );
        assertFalse( WebhookPayloads.SENDABLE.contains( WorkEvent.ATTACHMENT_REMOVED ) );
    }

    @Test
    void aSystemActorIsNamedForItsHostAndNeverLookedUp() {
        final Function< String, String > failing = login -> {
            throw new AssertionError( "the directory was asked for " + login );
        };
        final WebhookEvent w = new WebhookEvent( new WorkEvent( 5, 1L, 7L, "WIKI-7", AT, "codehost:github",
            Actor.Kind.SYSTEM, null, WorkEvent.CHANGE_MERGED, null, null, null ), "9", "WIKI", "t", WorkStatus.READY, "task" );
        final String body = WebhookPayloads.generic( w, "https://wiki.example.com", failing );
        assertTrue( body.contains( "\"actor\":\"GitHub\"" ), body );
        assertFalse( body.contains( "codehost" ), body );
    }

    @Test
    void aFlagIsNotSendable() {
        assertFalse( WebhookPayloads.SENDABLE.contains( WorkEvent.CHANGE_FLAGGED ), "a lead lane, not a channel feed" );
    }
}
