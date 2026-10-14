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
package com.wikantik.api.agent;

import com.wikantik.api.identity.PublicIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForAgentProjectionDisplayNamesTest {

    private static ForAgentProjection projection( final String verifiedBy, final List< RecentChange > changes ) {
        return new ForAgentProjection( "01ABC", "Slug", "Title", "article", null, null, null,
                null, verifiedBy, null, "s", null, null, changes, null, null, null, false,
                "/f", "/r", false, List.of(), List.of() );
    }

    /** One account, {@code janne} → {@code Janne Jalkanen}; every other value answers as the real service does. */
    private static PublicIdentity identity() {
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( identity.userIdForLogin( "janne" ) ).thenReturn( Optional.of( "uid-1" ) );
        when( identity.displayName( anyString() ) ).thenAnswer( call -> {
            final String stored = call.< String >getArgument( 0 ).trim();
            if ( "janne".equals( stored ) ) {
                return "Janne Jalkanen";
            }
            return stored.indexOf( '@' ) >= 0 ? PublicIdentity.UNKNOWN_CONTRIBUTOR : stored;
        } );
        return identity;
    }

    @Test
    void aMachineWrittenVerificationNoteShowsTheVerifiersDisplayName() {
        final ForAgentProjection p = projection( null, List.of(
                new RecentChange( 3, Instant.parse( "2026-04-23T11:10:00Z" ), "janne", "verified by janne" ),
                new RecentChange( 2, Instant.parse( "2026-04-22T11:10:00Z" ), null, "verified by gone.user@example.test" ),
                new RecentChange( 1, Instant.parse( "2026-04-21T11:10:00Z" ), "Legacy Person", "verified by hand" ) ) );

        final List< RecentChange > shown = p.withDisplayNames( identity() ).recentChanges();

        assertEquals( "verified by Janne Jalkanen", shown.get( 0 ).summary() );
        assertEquals( "verified by unknown contributor", shown.get( 1 ).summary(), "even where the version has no author" );
        assertEquals( "verified by hand", shown.get( 2 ).summary() );
        assertEquals( "verified by janne", p.recentChanges().get( 0 ).summary(), "the cached projection itself is never mutated" );
    }

    @Test
    void verifiedByAndRecentChangeAuthorsBecomeDisplayNames() {
        final ForAgentProjection p = projection( "janne", List.of(
                new RecentChange( 2, Instant.parse( "2026-04-22T11:10:00Z" ), "janne", "tidy" ),
                new RecentChange( 1, Instant.parse( "2026-04-21T11:10:00Z" ), "Legacy Person", null ) ) );

        final ForAgentProjection shown = p.withDisplayNames( identity() );

        assertEquals( "Janne Jalkanen", shown.verifiedBy() );
        assertEquals( "Janne Jalkanen", shown.recentChanges().get( 0 ).author() );
        assertEquals( "tidy", shown.recentChanges().get( 0 ).summary() );
        assertEquals( "Legacy Person", shown.recentChanges().get( 1 ).author() );
        assertEquals( "janne", p.verifiedBy(), "the cached projection itself is never mutated" );
    }

    @Test
    void aVerifierThatIsNotALoginIsKeptAsWritten() {
        assertEquals( "ops-team", projection( "ops-team", List.of() ).withDisplayNames( identity() ).verifiedBy() );
    }

    @Test
    void anAbsentAuthorStaysAbsent() {
        final ForAgentProjection p = projection( null,
                List.of( new RecentChange( 1, Instant.EPOCH, null, "note" ) ) );

        assertNull( p.withDisplayNames( identity() ).recentChanges().get( 0 ).author() );
    }

    @Test
    void withoutAnIdentityServiceTheProjectionIsReturnedAsIs() {
        final ForAgentProjection p = projection( "janne", List.of() );

        assertSame( p, p.withDisplayNames( null ) );
    }

    @Test
    void everyOtherFieldIsCarriedOver() {
        final ForAgentProjection p = projection( null, List.of() );

        assertEquals( p, p.withDisplayNames( identity() ) );
    }
}
