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
package com.wikantik.api.bundle;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleTypesTest {
    private static CitationHandle cite() {
        return new CitationHandle( "01ABC", 7, List.of( "Setup" ), "body span", "deadbeef" );
    }

    @Test
    void citation_requires_canonicalId_and_copies_path() {
        final CitationHandle c = cite();
        assertEquals( "01ABC", c.canonicalId() );
        assertEquals( List.of( "Setup" ), c.headingPath() );
        assertThrows( IllegalArgumentException.class,
            () -> new CitationHandle( " ", 1, List.of(), "x", "y" ) );
    }

    @Test
    void bundleSection_and_bundle_validate_and_copy() {
        final BundleSection s = new BundleSection( "01ABC", "DeployGuide", List.of( "Setup" ), "txt", 0.9, cite() );
        assertEquals( "DeployGuide", s.slug() );
        assertEquals( 0.9, s.score(), 1e-9 );
        final ContextBundle b = new ContextBundle( "how to deploy", List.of( s ) );
        assertEquals( 1, b.sections().size() );
        assertThrows( IllegalArgumentException.class, () -> new ContextBundle( null, List.of() ) );
    }
}
