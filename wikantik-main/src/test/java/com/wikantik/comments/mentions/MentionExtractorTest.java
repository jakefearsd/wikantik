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

package com.wikantik.comments.mentions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MentionExtractorTest {

    @Test
    void parse_empty_or_null_body_yields_empty_set() {
        assertTrue( MentionExtractor.parse( null ).isEmpty() );
        assertTrue( MentionExtractor.parse( "" ).isEmpty() );
        assertTrue( MentionExtractor.parse( "no at-signs here" ).isEmpty() );
    }

    @Test
    void parse_single_mention() {
        assertEquals( Set.of( "alice" ), MentionExtractor.parse( "hi @alice please look" ) );
    }

    @Test
    void parse_multiple_mentions_dedupes() {
        assertEquals( Set.of( "alice", "bob" ),
                MentionExtractor.parse( "@alice and @bob and @alice again" ) );
    }

    @Test
    void parse_strips_trailing_punctuation() {
        assertEquals( Set.of( "alice", "bob", "carol" ),
                MentionExtractor.parse( "@alice, hello — @bob. Also @carol!" ) );
    }

    @Test
    void parse_supports_dot_underscore_hyphen_in_login_names() {
        assertEquals( Set.of( "test.bot", "user_1", "alice-1" ),
                MentionExtractor.parse( "@test.bot @user_1 @alice-1" ) );
    }

    @Test
    void parse_does_not_match_inside_email_addresses() {
        // alice@example.com is one email token — the '@' has a word-char prefix
        // ('e' from 'alice'), so the lookbehind fails and NO mentions are
        // extracted. Neither the local part NOR the domain should be matched.
        final Set< String > got = MentionExtractor.parse( "email alice@example.com sent" );
        assertFalse( got.contains( "alice" ), "local part is not a mention" );
        assertFalse( got.contains( "example" ), "domain is not a mention" );
        assertFalse( got.contains( "example.com" ), "domain.tld is not a mention" );
    }

    @Test
    void parse_matches_mention_after_email_when_separated_by_whitespace() {
        // After the email is finished, @bob is a real mention (preceded by space).
        final Set< String > got = MentionExtractor.parse( "alice@example.com cc @bob" );
        assertEquals( Set.of( "bob" ), got );
    }

    // ---- extract(): tokens + legacy ------------------------------------------------------

    @Test
    void extract_empty_or_null_body_has_no_mentions() {
        assertTrue( MentionExtractor.extract( null ).isEmpty() );
        assertTrue( MentionExtractor.extract( "" ).isEmpty() );
        assertTrue( MentionExtractor.extract( "no mentions here" ).isEmpty() );
    }

    @Test
    void extract_reads_the_uid_from_a_token() {
        final MentionExtractor.Mentions m = MentionExtractor.extract( "hi @[Alice A](user:u-alice) please look" );
        assertEquals( Set.of( "u-alice" ), m.userIds() );
        assertTrue( m.legacyLogins().isEmpty() );
    }

    @Test
    void extract_finds_several_distinct_tokens_and_dedupes_repeats() {
        final MentionExtractor.Mentions m = MentionExtractor.extract(
                "@[A](user:1) and @[B](user:2) and @[A again](user:1)@[C](user:3)" );
        assertEquals( Set.of( "1", "2", "3" ), m.userIds() );
    }

    @Test
    void extract_accepts_every_uid_shape_in_the_users_table() {
        // UUIDs, the legacy negative-number uids seeded by V002, and slug uids like the agents account.
        final MentionExtractor.Mentions m = MentionExtractor.extract(
                "@[U](user:7f3c2d1e-0a4b-4c5d-8e9f-001122334455) @[Admin](user:-6852820166199419346) "
                + "@[Agents](user:agents-service-account)" );
        assertEquals( Set.of( "7f3c2d1e-0a4b-4c5d-8e9f-001122334455", "-6852820166199419346",
                "agents-service-account" ), m.userIds() );
    }

    @Test
    void extract_accepts_the_64_character_bounds_and_rejects_65() {
        final String name64 = "n".repeat( 64 );
        final String uid64 = "u".repeat( 64 );
        assertEquals( Set.of( uid64 ),
                MentionExtractor.extract( "@[" + name64 + "](user:" + uid64 + ")" ).userIds() );
        assertTrue( MentionExtractor.extract( "@[" + "n".repeat( 65 ) + "](user:u1)" ).userIds().isEmpty(),
                "a 65-character display name is not a token" );
        assertTrue( MentionExtractor.extract( "@[Alice](user:" + "u".repeat( 65 ) + ")" ).userIds().isEmpty(),
                "a 65-character uid is not a token" );
    }

    @Test
    void extract_ignores_malformed_tokens() {
        for ( final String bad : new String[] {
                "@[](user:u1)",                  // empty display text
                "@[Alice](user:)",               // empty uid
                "@[Alice](user:bad uid)",        // space in uid
                "@[Alice](user:u_1)",            // underscore is not a uid character
                "@[Alice](user:u1",              // unterminated
                "@[Alice](usr:u1)",              // wrong scheme
                "@[Alice] (user:u1)",            // gap between ] and (
                "@[Ali\nce](user:u1)",           // newline inside the display text
                "@Alice(user:u1)",               // no brackets
                "[Alice](user:u1)" } ) {         // no @
            assertTrue( MentionExtractor.extract( bad ).userIds().isEmpty(), "must not parse: " + bad );
        }
    }

    @Test
    void extract_display_text_may_hold_parentheses_colons_and_unicode() {
        assertEquals( Set.of( "u1" ), MentionExtractor.extract( "@[Zo\u00eb (Ng): QA](user:u1)" ).userIds() );
    }

    @Test
    void extract_counts_a_token_inside_backticks_because_comments_are_plain_text() {
        assertEquals( Set.of( "u1" ), MentionExtractor.extract( "see `@[Alice](user:u1)` here" ).userIds() );
    }

    @Test
    void extract_still_reads_legacy_at_logins() {
        final MentionExtractor.Mentions m = MentionExtractor.extract( "ping @bob, and @[Alice](user:u1)" );
        assertEquals( Set.of( "u1" ), m.userIds() );
        assertEquals( Set.of( "bob" ), m.legacyLogins() );
    }

    @Test
    void extract_does_not_read_an_at_inside_a_token_snapshot_as_a_legacy_login() {
        final MentionExtractor.Mentions m = MentionExtractor.extract( "x @[Bob @carol](user:u2) y" );
        assertEquals( Set.of( "u2" ), m.userIds() );
        assertTrue( m.legacyLogins().isEmpty(), "@carol sits inside the snapshot, not in the text" );
    }

    @Test
    void extract_an_email_login_is_reachable_only_through_a_token() {
        // alice@example.com can never match the legacy form (the '@' has a word-char prefix)...
        assertTrue( MentionExtractor.extract( "cc alice@example.com" ).isEmpty() );
        // ...but the same member is addressable by uid.
        assertEquals( Set.of( "u-email" ), MentionExtractor.extract( "cc @[Alice A](user:u-email)" ).userIds() );
    }

    @Test
    void parse_does_not_recognise_tokens() {
        assertTrue( MentionExtractor.parse( "@[Alice](user:u1)" ).isEmpty() );
    }

    @Test
    void resolve_filters_to_existing_logins_only() {
        final Set< String > tokens = Set.of( "alice", "ghost", "bob" );
        final Set< String > resolved = MentionExtractor.resolve( tokens, List.of( "alice", "bob", "admin" )::contains );
        assertEquals( Set.of( "alice", "bob" ), resolved );
    }
}
