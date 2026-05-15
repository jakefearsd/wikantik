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

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class AudienceTest {

    @Test
    void wireNamesAreStable() {
        assertEquals( "humans", Audience.HUMANS.wireName() );
        assertEquals( "agents", Audience.AGENTS.wireName() );
        assertEquals( "humans-and-agents", Audience.HUMANS_AND_AGENTS.wireName() );
    }

    @Test
    void fromFrontmatterDefaultsWhenAbsent() {
        assertEquals( Audience.HUMANS_AND_AGENTS, Audience.fromFrontmatter( null ) );
    }

    @Test
    void fromFrontmatterReadsListForm() {
        assertEquals( Audience.HUMANS_AND_AGENTS, Audience.fromFrontmatter( List.of( "humans", "agents" ) ) );
        assertEquals( Audience.AGENTS, Audience.fromFrontmatter( List.of( "agents" ) ) );
        assertEquals( Audience.HUMANS, Audience.fromFrontmatter( List.of( "humans" ) ) );
        assertEquals( Audience.HUMANS_AND_AGENTS, Audience.fromFrontmatter( List.of() ) );
    }

    @Test
    void fromFrontmatterListFormTrimsAndLowercasesAndIgnoresNulls() {
        assertEquals( Audience.HUMANS_AND_AGENTS,
                Audience.fromFrontmatter( Arrays.asList( "  HUMANS ", null, "Agents" ) ) );
    }

    @Test
    void fromFrontmatterReadsCanonicalStringForm() {
        assertEquals( Audience.AGENTS, Audience.fromFrontmatter( "agents" ) );
        assertEquals( Audience.HUMANS_AND_AGENTS, Audience.fromFrontmatter( "humans-and-agents" ) );
    }

    @Test
    void fromFrontmatterDefaultsOnUnrecognizedString() {
        assertEquals( Audience.HUMANS_AND_AGENTS, Audience.fromFrontmatter( "nonsense" ) );
    }

    @Test
    void fromWireParsesKnownValuesCaseInsensitively() {
        assertEquals( Audience.AGENTS, Audience.fromWire( " AGENTS " ).orElseThrow() );
        assertEquals( Audience.HUMANS, Audience.fromWire( "humans" ).orElseThrow() );
    }

    @Test
    void fromWireEmptyForNullOrUnknown() {
        assertTrue( Audience.fromWire( null ).isEmpty() );
        assertTrue( Audience.fromWire( "garbage" ).isEmpty() );
    }
}
