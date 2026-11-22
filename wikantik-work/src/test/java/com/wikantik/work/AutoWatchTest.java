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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoWatchTest {

    private static List< AutoWatch.Change > of( final WorkEvent e, final Set< String > mentioned, final boolean removed ) {
        return AutoWatch.of( e, "rita", mentioned, removed );
    }

    private static WorkEvent agent( final String type, final String to ) {
        return RecipientsTest.ev( type, "codex", Actor.Kind.AGENT, null, to, null );
    }

    @Test
    void aPersonWhoReportsWatches() {
        assertEquals( List.of( new AutoWatch.Change( "rita", false ) ),
            of( RecipientsTest.ev( WorkEvent.CREATED, "rita", Actor.Kind.HUMAN, null, null, null ), Set.of(), false ) );
    }

    @Test
    void anAgentWhoReportsWatchesNothing() {
        assertTrue( AutoWatch.of( agent( WorkEvent.CREATED, null ), null, Set.of(), false ).isEmpty() );
        assertTrue( of( agent( WorkEvent.CREATED, null ), Set.of(), false ).isEmpty() );
    }

    @Test
    void anAssigneeIsSubscribed() {
        assertEquals( List.of( new AutoWatch.Change( "bob", true ) ),
            of( RecipientsTest.byAlice( WorkEvent.ASSIGNED, "bob" ), Set.of(), false ) );
        assertEquals( List.of( new AutoWatch.Change( "bob", true ) ),
            of( RecipientsTest.byAlice( WorkEvent.HANDOFF, "in_review|bob" ), Set.of(), false ) );
        assertTrue( of( RecipientsTest.byAlice( WorkEvent.ASSIGNED, "queue:ops" ), Set.of(), false ).isEmpty() );
    }

    @Test
    void aCommenterWatchesAndTheMentionedAreSubscribed() {
        assertEquals( List.of( new AutoWatch.Change( "alice", false ), new AutoWatch.Change( "carl", true ) ),
            of( RecipientsTest.byAlice( WorkEvent.COMMENTED, "5" ), Set.of( "carl" ), false ) );
        assertEquals( List.of( new AutoWatch.Change( "carl", true ) ),
            of( RecipientsTest.byAlice( WorkEvent.COMMENT_EDITED, "5" ), Set.of( "carl" ), false ) );
    }

    @Test
    void anAgentCommenterWatchesNothingButItsMentionsSubscribe() {
        assertEquals( List.of( new AutoWatch.Change( "carl", true ) ),
            of( agent( WorkEvent.COMMENTED, "5" ), Set.of( "carl" ), false ) );
    }

    @Test
    void aRemovedCommentSubscribesNobody() {
        assertEquals( List.of( new AutoWatch.Change( "alice", false ) ),
            of( RecipientsTest.byAlice( WorkEvent.COMMENTED, "5" ), Set.of( "carl" ), true ) );
        assertTrue( of( RecipientsTest.byAlice( WorkEvent.COMMENT_EDITED, "5" ), Set.of( "carl" ), true ).isEmpty() );
    }
}
