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
package com.wikantik.api.eval;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleRecordsTest {

    @Test
    void goldSection_normalizes_and_rejects_blank_id() {
        final GoldSection g = new GoldSection( "01ABC", List.of( "Overview", "Setup" ) );
        assertEquals( "01ABC", g.canonicalId() );
        assertEquals( List.of( "Overview", "Setup" ), g.headingPath() );
        assertThrows( IllegalArgumentException.class,
            () -> new GoldSection( "  ", List.of() ) );
    }

    @Test
    void bundleSection_defensively_copies_and_requires_text() {
        final BundleSection s = new BundleSection( "01ABC", List.of( "Overview" ), "body text" );
        assertEquals( "body text", s.text() );
        assertThrows( IllegalArgumentException.class,
            () -> new BundleSection( "01ABC", List.of( "Overview" ), null ) );
    }

    @Test
    void question_requires_at_least_one_gold() {
        final BundleEvalQuestion q = new BundleEvalQuestion(
            "q1", "how do I deploy locally", BundleCategory.SIMILARITY,
            List.of( new GoldSection( "01DEP", List.of( "Deploy" ) ) ) );
        assertEquals( BundleCategory.SIMILARITY, q.category() );
        assertEquals( 1, q.goldSections().size() );
        assertThrows( IllegalArgumentException.class,
            () -> new BundleEvalQuestion( "q2", "x", BundleCategory.RELATIONAL, List.of() ) );
    }
}
