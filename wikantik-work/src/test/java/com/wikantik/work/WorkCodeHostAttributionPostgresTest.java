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
import java.util.List;

import static com.wikantik.work.CodeEvents.A;
import static com.wikantik.work.CodeEvents.ANA;
import static com.wikantik.work.CodeEvents.M;
import static com.wikantik.work.CodeEvents.at;
import static com.wikantik.work.CodeEvents.commit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Who a push is attributed to (M-1): the pusher the host asserts, through the identity mapping, never the address a
 * commit carries (anyone with push rights can write any address into a commit). A commit author still teaches the
 * username mapping; it is never the person the change is attributed to.
 */
@RequiresPostgres
class WorkCodeHostAttributionPostgresTest {

    /** Someone with push rights, unknown to the wiki, who wrote Ana's address into the commit. */
    private static final CodeEvent.Person MALLORY = new CodeEvent.Person( "mallorygh", null, null );
    /** Ana's own account login, as the host asserts it for the pusher. */
    private static final CodeEvent.Person ANA_LOGIN = new CodeEvent.Person( "anagh", null, null );

    private CodeHostFixture f;
    private HookDelivery d;
    private Item item;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        d = f.delivery( "github" );
        item = f.task();
    }

    private void apply( final CodeEvent e ) throws SQLException {
        f.hooks.apply( d, List.of( e ) );
    }

    @Test
    void aDirectPushIsTheUnmatchedPushersNotTheCommitAddressOwners() throws SQLException {
        apply( CodeEvents.pushBy( "main", M, at( "12:00" ), MALLORY,
            commit( M, item.key() + " hotfix", at( "11:50" ), true, ANA ) ) );
        final Change c = f.only( item );
        assertNull( c.mergedBy(), "the commit's address names Ana, but Ana did not push" );
        assertEquals( "mallorygh", c.externalMergedBy() );
        assertNull( c.openedBy() );
        assertEquals( "mallorygh", c.externalOpenedBy() );
        assertEquals( "direct", c.policyFlag(), "the flag does not depend on who pushed" );
    }

    @Test
    void aDirectPushByAMappedPusherIsTheirs() throws SQLException {
        // the commit by Ana's account (host-asserted username with her address) teaches anagh -> ana, then the pusher
        // anagh resolves through the mapping
        apply( CodeEvents.pushBy( "main", M, at( "12:00" ), ANA_LOGIN,
            commit( M, item.key() + " hotfix", at( "11:50" ), true, ANA ) ) );
        final Change c = f.only( item );
        assertEquals( "ana", c.mergedBy() );
        assertEquals( "ana", c.openedBy() );
    }

    @Test
    void aDirectPushWithNoPusherIsNobodys() throws SQLException {
        apply( CodeEvents.pushBy( "main", M, at( "12:00" ), null,
            commit( M, item.key() + " hotfix", at( "11:50" ), true, ANA ) ) );
        final Change c = f.only( item );
        assertNull( c.mergedBy() );
        assertEquals( "unknown", c.externalMergedBy() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_codehost_identity WHERE username = 'anagh' AND login = 'ana'" ),
            "the commit author still teaches the mapping" );
    }

    @Test
    void aBranchPushOpensTheChangeForThePusherNotTheCommitAddress() throws SQLException {
        final String branch = "work/" + item.key() + "-fix";
        apply( CodeEvents.pushBy( branch, A, at( "11:00" ), MALLORY, commit( A, "start", at( "10:50" ), true, ANA ) ) );
        final Change c = f.only( item );
        assertNull( c.openedBy() );
        assertEquals( "mallorygh", c.externalOpenedBy() );
    }

    @Test
    void aCommitWithNoHostAssertedUsernameTeachesNothingAndNamesNobody() throws SQLException {
        apply( CodeEvents.pushBy( "main", M, at( "12:00" ), MALLORY,
            commit( M, item.key() + " hotfix", at( "11:50" ), true, new CodeEvent.Person( null, "Ana A",
                "ana@example.com" ) ) ) );
        assertNull( f.only( item ).mergedBy() );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_codehost_identity" ) );
    }
}
