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

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every time a delivery or a caller can give is bounded to 1970..now, and none of them throws anything else. */
class CodeHostTimesTest {

    private static final Instant NOW = Instant.parse( "2026-03-01T12:00:00Z" );

    @Test
    void hostTimesOutsideTheRangeAreDroppedOrClamped() {
        assertNull( CodeHostTimes.bounded( ( Instant ) null, NOW ) );
        assertNull( CodeHostTimes.bounded( Instant.MIN, NOW ) );
        assertNull( CodeHostTimes.bounded( Instant.parse( "0001-01-01T00:00:00Z" ), NOW ) );
        assertNull( CodeHostTimes.bounded( Instant.parse( "1969-12-31T23:59:59Z" ), NOW ) );
        assertEquals( Instant.EPOCH, CodeHostTimes.bounded( Instant.EPOCH, NOW ) );
        assertEquals( Instant.parse( "1999-01-01T00:00:00Z" ),
            CodeHostTimes.bounded( Instant.parse( "1999-01-01T00:00:00Z" ), NOW ), "old but valid: as given" );
        assertEquals( NOW, CodeHostTimes.bounded( Instant.MAX, NOW ) );
        assertEquals( NOW, CodeHostTimes.bounded( Instant.parse( "9999-12-31T23:59:59Z" ), NOW ) );
    }

    @Test
    void anEventIsBoundedWithEveryCommit() {
        final CodeEvent e = new CodeEvent( CodeEvent.Kind.PUSH, "acme/wk", "main", "b", null, null, "aaaaaaa1", Instant.MIN,
            List.of( new CodeEvent.Commit( "aaaaaaa1", "m", Instant.MAX, true, null ),
                new CodeEvent.Commit( "bbbbbbb2", "m", Instant.parse( "0001-01-01T00:00:00Z" ), true, null ) ),
            null, null, null );
        final CodeEvent b = CodeHostTimes.bounded( e, NOW );
        assertNull( b.at() );
        assertEquals( NOW, b.commits().get( 0 ).at() );
        assertNull( b.commits().get( 1 ).at() );
    }

    @Test
    void aReportedFirstCommitOutsideTheRangeIsAValidationErrorNeverAnotherException() {
        for ( final String raw : List.of( "-999999999-01-01T00:00:00Z", "0001-01-01T00:00:00Z", "1969-12-31T23:59:59Z",
                "+999999999-12-31T23:59:59Z", "+1000000000-01-01T00:00:00Z", "9999-12-31T23:59:59Z" ) ) {
            assertEquals( "firstCommitAt", assertThrows( WorkValidationException.class,
                () -> ChangeInputs.firstCommitAt( raw, NOW ), raw ).field(), raw );
        }
        assertEquals( Instant.EPOCH, ChangeInputs.firstCommitAt( "1970-01-01T00:00:00Z", NOW ) );
    }
}
