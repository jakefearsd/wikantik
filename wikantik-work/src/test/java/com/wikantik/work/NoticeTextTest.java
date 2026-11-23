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

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NoticeTextTest {

    private static final Function< String, String > NAMES = login -> switch ( login ) {
        case "lena" -> "Lena Lead";
        case "bob" -> "Bob B";
        default -> "Alice A";
    };

    private static WorkEvent e( final String type, final String from, final String to, final String note ) {
        return RecipientsTest.ev( type, "alice", Actor.Kind.HUMAN, from, to, note );
    }

    private static String line( final WorkEvent e, final NoticeReason r, final String recipient ) {
        return NoticeText.line( e, r, recipient, NAMES );
    }

    @Test
    void assignment() {
        assertEquals( "Alice A assigned this to you", line( e( "assigned", null, "bob", null ), null, "bob" ) );
        assertEquals( "Alice A assigned this to Bob B", line( e( "assigned", null, "bob", null ), null, null ) );
        assertEquals( "Alice A assigned this to the ops queue", line( e( "assigned", null, "queue:ops", null ), null, null ) );
        assertEquals( "Alice A unassigned this", line( e( "assigned", "bob", null, null ), null, null ) );
    }

    @Test
    void handoff() {
        assertEquals( "Alice A handed this to you as in review",
            line( e( "handoff", null, "in_review|bob", null ), null, "bob" ) );
        assertEquals( "Alice A handed this to the ops queue as todo",
            line( e( "handoff", null, "todo|queue:ops", null ), null, null ) );
    }

    @Test
    void statusMove() {
        assertEquals( "Alice A moved this from in progress to in review",
            line( e( "status", "in_progress", "in_review", null ), null, null ) );
    }

    @Test
    void comments() {
        assertEquals( "Alice A mentioned you in a comment", line( e( "commented", null, "5", null ), NoticeReason.MENTIONED, "bob" ) );
        assertEquals( "Alice A commented", line( e( "commented", null, "5", null ), NoticeReason.WATCHING, "bob" ) );
        assertEquals( "Alice A mentioned you in an edited comment", line( e( "comment_edited", null, "5", null ), NoticeReason.MENTIONED, "bob" ) );
    }

    @Test
    void changesAndDeployments() {
        assertEquals( "Alice A asked for a review", line( e( "review_requested", null, null, null ), null, null ) );
        assertEquals( "Alice A merged a change", line( e( "change_merged", null, null, null ), null, null ) );
        assertEquals( "Alice A shipped this", line( e( "shipped", null, null, null ), null, null ) );
        assertEquals( "Alice A rolled back a deployment that carried this", line( e( "rolled_back", null, null, null ), null, null ) );
    }

    @Test
    void claimLapse() {
        final WorkEvent lapse = RecipientsTest.ev( "claim_expired", "codex", Actor.Kind.AGENT, null, null, null );
        assertEquals( "codex's claim lapsed and the item went back to its queue", line( lapse, null, null ) );
    }

    @Test
    void webhookSwitchedOff() {
        assertEquals( "The webhook to hooks.example.com was switched off after failing for 24 hours.",
            line( e( "settings", "hooks.example.com", "failed", "webhook" ), NoticeReason.WEBHOOK_DISABLED, "lena" ) );
    }

    @Test
    void anythingElse() {
        assertEquals( "Alice A updated this", line( e( "edited", null, null, null ), null, null ) );
        assertEquals( "Alice A updated this", line( e( "settings", null, "x", "name" ), null, null ) );
    }

    @Test
    void anAgentIsNamedByItsName() {
        final WorkEvent a = RecipientsTest.ev( "shipped", "codex", Actor.Kind.AGENT, null, null, null );
        assertEquals( "codex shipped this", line( a, null, null ) );
    }

    @Test
    void aLoginNeverAppears() {
        final WorkEvent a = RecipientsTest.ev( "assigned", "lena", Actor.Kind.HUMAN, null, "bob", null );
        final String text = line( a, null, null );
        assertEquals( "Lena Lead assigned this to Bob B", text );
        assertFalse( text.contains( "lena" ) );
    }

    @Test
    void aNoteIsNeverPrinted() {
        assertFalse( line( e( "status", "todo", "done", "secret note" ), null, null ).contains( "secret" ) );
    }

    @Test
    void aSystemActorIsNamedForItsHostAndNeverLookedUp() {
        final Function< String, String > failing = login -> {
            throw new AssertionError( "the directory was asked for " + login );
        };
        final WorkEvent merged = RecipientsTest.ev( "change_merged", "codehost:github", Actor.Kind.SYSTEM, null, null, null );
        assertEquals( "GitHub merged a change", NoticeText.line( merged, null, null, failing ) );
        final WorkEvent other = RecipientsTest.ev( "change_merged", "codehost:gitea", Actor.Kind.SYSTEM, null, null, null );
        assertEquals( "Code host merged a change", NoticeText.line( other, null, null, failing ) );
    }

    @Test
    void aFlagLineNamesNoReader() {
        final WorkEvent flag = RecipientsTest.ev( "change_flagged", "codehost:github", Actor.Kind.SYSTEM, null, null, null );
        assertEquals( "GitHub flagged a merge outside policy", line( flag, NoticeReason.ACKNOWLEDGE, "lena" ) );
    }

    @Test
    void aFilingIsARequestToALeadAndACreationToAnyoneElse() {
        assertEquals( "Alice A filed a request", line( e( "created", null, null, null ), NoticeReason.REQUESTED, "lena" ) );
        assertEquals( "Alice A created this", line( e( "created", null, null, null ), null, null ) );
    }
}
