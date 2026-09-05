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
package com.wikantik.rest;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link InsightsIngestResource}'s allowlist parsing.
 *
 * <p>ini/wikantik.properties ships {@code wikantik.insights.ingest.sites} blank (the reader's own
 * default is the wildcard {@code *}, i.e. "accept every site"). A blank value present in the
 * properties file must resolve the same way as the key being entirely absent — not to an
 * empty allowlist, which would reject every request.</p>
 */
class InsightsIngestResourceTest {

    @Test
    void resolveAllowlistTreatsBlankValueAsAbsent() {
        assertEquals( Set.of( "*" ), InsightsIngestResource.resolveAllowlist( "", "*" ) );
    }

    @Test
    void resolveAllowlistTreatsNullValueAsAbsent() {
        assertEquals( Set.of( "*" ), InsightsIngestResource.resolveAllowlist( null, "*" ) );
    }

    @Test
    void resolveAllowlistParsesCommaSeparatedValueTrimmingEntries() {
        assertEquals( Set.of( "google", "bing" ), InsightsIngestResource.resolveAllowlist( "google, bing", "*" ) );
    }

    @Test
    void resolveAllowlistUsesTheNonWildcardDefaultWhenAbsent() {
        assertEquals( Set.of( "google", "bing", "yandex" ),
            InsightsIngestResource.resolveAllowlist( null, "google,bing,yandex" ) );
    }
}
