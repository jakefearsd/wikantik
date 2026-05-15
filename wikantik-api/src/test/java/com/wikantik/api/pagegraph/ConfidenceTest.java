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
package com.wikantik.api.pagegraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfidenceTest {

    @Test
    void wireNamesAreStable() {
        assertEquals( "authoritative", Confidence.AUTHORITATIVE.wireName() );
        assertEquals( "provisional", Confidence.PROVISIONAL.wireName() );
        assertEquals( "stale", Confidence.STALE.wireName() );
    }

    @Test
    void fromWireParsesKnownValuesCaseInsensitivelyAndTrimmed() {
        assertEquals( Confidence.STALE, Confidence.fromWire( "stale" ).orElseThrow() );
        assertEquals( Confidence.AUTHORITATIVE, Confidence.fromWire( " Authoritative " ).orElseThrow() );
        assertEquals( Confidence.PROVISIONAL, Confidence.fromWire( "PROVISIONAL" ).orElseThrow() );
    }

    @Test
    void fromWireAcceptsNonStringByToString() {
        assertEquals( Confidence.STALE, Confidence.fromWire( new StringBuilder( "stale" ) ).orElseThrow() );
    }

    @Test
    void fromWireEmptyForNullOrUnknown() {
        assertTrue( Confidence.fromWire( null ).isEmpty() );
        assertTrue( Confidence.fromWire( "unknown" ).isEmpty() );
    }
}
