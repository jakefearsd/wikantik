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

    @Test
    void resolve_filters_to_existing_logins_only() {
        final Set< String > tokens = Set.of( "alice", "ghost", "bob" );
        final Set< String > resolved = MentionExtractor.resolve( tokens, List.of( "alice", "bob", "admin" )::contains );
        assertEquals( Set.of( "alice", "bob" ), resolved );
    }
}
