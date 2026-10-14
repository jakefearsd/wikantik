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
package com.wikantik.api.identity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class IdentityDisplayTest {

    /**
     * Known accounts: login {@code janne} → {@code Janne Jalkanen}; {@code nulluid} → {@code Null Uid Person}, an account
     * whose stored uid is NULL (until the one-shot runs), so {@code userIdForLogin} finds nothing for it; and
     * {@code sso.user@example.test} → {@code Sso User}, an SSO account whose login is its email. Behaves like the real
     * service otherwise.
     */
    private static final class FakeIdentity implements PublicIdentity {
        private final Map< String, String > namesByLogin = Map.of( "janne", "Janne Jalkanen", "nulluid", "Null Uid Person",
                "sso.user@example.test", "Sso User" );

        @Override public String displayName( final String storedPrincipal ) {
            if ( storedPrincipal == null || storedPrincipal.isBlank() ) {
                return UNKNOWN;
            }
            final String stored = storedPrincipal.trim();
            if ( namesByLogin.containsKey( stored ) ) {
                return namesByLogin.get( stored );
            }
            return stored.indexOf( '@' ) >= 0 ? UNKNOWN_CONTRIBUTOR : stored;
        }
        @Override public Optional< String > userIdForLogin( final String login ) {
            return namesByLogin.containsKey( login ) && !"nulluid".equals( login ) ? Optional.of( "uid-" + login ) : Optional.empty();
        }
        @Override public Optional< String > loginForUserId( final String uid ) { return Optional.empty(); }
        @Override public Optional< String > loginForDisplayName( final String displayName ) { return Optional.empty(); }
        @Override public void invalidate( final String login ) { }
    }

    private final PublicIdentity identity = new FakeIdentity();

    @Test
    void ofAuthor_resolvesALoginToItsDisplayName() {
        assertEquals( "Janne Jalkanen", IdentityDisplay.ofAuthor( identity, "janne" ) );
    }

    @Test
    void ofAuthor_leavesLegacyFullNamesAndAgentLabelsAlone() {
        assertEquals( "Legacy Person", IdentityDisplay.ofAuthor( identity, "Legacy Person" ) );
        assertEquals( "claude-code-researcher", IdentityDisplay.ofAuthor( identity, "claude-code-researcher" ) );
    }

    @Test
    void ofAuthor_masksAnEmailShapedValueThatIsNoAccount() {
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, IdentityDisplay.ofAuthor( identity, "gone.user@example.test" ) );
    }

    @Test
    void ofAuthor_keepsAnAbsentAuthorAbsent() {
        assertNull( IdentityDisplay.ofAuthor( identity, null ) );
    }

    @Test
    void ofAuthor_withoutAnIdentityServiceReturnsTheStoredValue() {
        assertEquals( "janne", IdentityDisplay.ofAuthor( null, "janne" ) );
    }

    @Test
    void ofLoginOrText_resolvesOnlyExistingLogins() {
        assertEquals( "Janne Jalkanen", IdentityDisplay.ofLoginOrText( identity, "janne" ) );
        assertEquals( "claude-code-researcher", IdentityDisplay.ofLoginOrText( identity, "claude-code-researcher" ) );
    }

    @Test
    void ofLoginOrText_resolvesTheLoginOfAnAccountWithNoStoredUid() {
        // Login-ness is decided by the account lookup itself, never by the stored uid: a NULL-uid account's login
        // is still a login, and must not be shown as written.
        assertEquals( "Null Uid Person", IdentityDisplay.ofLoginOrText( identity, "nulluid" ) );
    }

    @Test
    void ofLoginOrText_alwaysMasksAnEmailShapedValue() {
        // A deleted SSO account's login (an email) left in verified_by/author is no account any more; it must not be
        // shown as written. An email-shaped login of a live account resolves like any login.
        assertEquals( PublicIdentity.UNKNOWN_CONTRIBUTOR, IdentityDisplay.ofLoginOrText( identity, "gone.user@example.test" ) );
        assertEquals( "Sso User", IdentityDisplay.ofLoginOrText( identity, "sso.user@example.test" ) );
    }

    @Test
    void ofLoginOrText_showsOtherTypedTextExactlyAsWritten() {
        assertEquals( "  ops-team ", IdentityDisplay.ofLoginOrText( identity, "  ops-team " ), "not a login: byte for byte" );
        assertEquals( "  ", IdentityDisplay.ofLoginOrText( identity, "  " ) );
        assertNull( IdentityDisplay.ofLoginOrText( identity, null ) );
        assertEquals( "someone@company.test", IdentityDisplay.ofLoginOrText( null, "someone@company.test" ),
                "without an identity service values pass through" );
    }

    @Test
    void changeNote_resolvesTheVerifierOfAMachineWrittenVerificationNote() {
        assertEquals( "verified by Janne Jalkanen", IdentityDisplay.changeNote( identity, "verified by janne" ) );
        assertEquals( "verified by Null Uid Person", IdentityDisplay.changeNote( identity, "verified by nulluid" ) );
        assertEquals( "verified by Sso User", IdentityDisplay.changeNote( identity, "verified by sso.user@example.test" ) );
        assertEquals( "verified by Janne Jalkanen (quarterly review)",
                IdentityDisplay.changeNote( identity, "verified by janne (quarterly review)" ), "the rest of the note is kept" );
    }

    @Test
    void changeNote_masksAVerifierThatIsAnEmailButNoAccount() {
        assertEquals( "verified by unknown contributor", IdentityDisplay.changeNote( identity, "verified by gone.user@example.test" ) );
    }

    @Test
    void changeNote_leavesEveryOtherNoteAsWritten() {
        assertEquals( "verified by hand", IdentityDisplay.changeNote( identity, "verified by hand" ) );
        assertEquals( "verified by Jane Doe after the audit", IdentityDisplay.changeNote( identity, "verified by Jane Doe after the audit" ) );
        assertEquals( "tidy: janne asked for it", IdentityDisplay.changeNote( identity, "tidy: janne asked for it" ),
                "only the machine-written verification shape is rewritten" );
        assertEquals( "Verified by janne", IdentityDisplay.changeNote( identity, "Verified by janne" ) );
        assertEquals( "", IdentityDisplay.changeNote( identity, "" ) );
        assertNull( IdentityDisplay.changeNote( identity, null ) );
        assertEquals( "verified by janne", IdentityDisplay.changeNote( null, "verified by janne" ) );
    }

    @Test
    void frontmatter_resolvesAuthorAndVerifiedByWhenTheyAreLoginsAndNothingElse() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "type", "article" );
        metadata.put( "author", "janne" );
        metadata.put( "verified_by", "ops-team" );
        metadata.put( "reviewer", "janne" );
        metadata.put( "tags", List.of( "janne" ) );

        final Map< String, Object > shown = IdentityDisplay.frontmatter( identity, metadata );

        assertEquals( "Janne Jalkanen", shown.get( "author" ) );
        assertEquals( "ops-team", shown.get( "verified_by" ) );
        assertEquals( "janne", shown.get( "reviewer" ), "only author and verified_by name a principal" );
        assertEquals( List.of( "janne" ), shown.get( "tags" ) );
        assertEquals( List.of( "type", "author", "verified_by", "reviewer", "tags" ), List.copyOf( shown.keySet() ),
                "key order is preserved" );
        assertEquals( "janne", metadata.get( "author" ), "the caller's map is never mutated" );
    }

    @Test
    void frontmatter_returnsTheSameInstanceWhenNothingChanges() {
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "author", "claude-code-researcher" );
        metadata.put( "verified_by", List.of( "not", "a", "string" ) );

        assertSame( metadata, IdentityDisplay.frontmatter( identity, metadata ) );
        assertSame( metadata, IdentityDisplay.frontmatter( null, metadata ) );
        assertNull( IdentityDisplay.frontmatter( identity, null ) );
    }
}
