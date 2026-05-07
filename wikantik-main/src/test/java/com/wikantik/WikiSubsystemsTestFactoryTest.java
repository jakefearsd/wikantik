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
package com.wikantik;

import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.auth.UserManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class WikiSubsystemsTestFactoryTest {

    @Test
    void defaults_returns_bundle_with_every_subsystem_populated() {
        final WikiSubsystems subs = WikiSubsystemsTestFactory.defaults();

        assertNotNull( subs.core(),        "core" );
        assertNotNull( subs.persistence(), "persistence" );
        assertNotNull( subs.auth(),        "auth" );
        assertNotNull( subs.page(),        "page" );
        assertNotNull( subs.rendering(),   "rendering" );
        assertNotNull( subs.search(),      "search" );
        assertNotNull( subs.knowledge(),   "knowledge" );
        assertNotNull( subs.pageGraph(),   "pageGraph" );

        // Every Services-record component should also be non-null (Mockito mock).
        assertNotNull( subs.auth().users(),     "auth.users mock" );
        assertNotNull( subs.page().pages(),     "page.pages mock" );
        assertNotNull( subs.rendering().pluginManager(), "rendering.pluginManager mock" );
    }

    @Test
    void builder_honours_overrides() {
        final UserManager userMock = Mockito.mock( UserManager.class );
        final AuthSubsystem.Services authOverride = WikiSubsystemsTestFactory.mockRecord(
            AuthSubsystem.Services.class );
        // We can't easily replace one component of a record post-construction,
        // so use a fresh builder + verify the override is honoured at the
        // bundle level.
        final WikiSubsystems subs = WikiSubsystemsTestFactory.builder()
            .auth( authOverride )
            .build();

        assertSame( authOverride, subs.auth(),
            "builder should preserve injected auth services record" );
        assertNotNull( subs.knowledge(),
            "non-overridden subsystems still get a default mock" );
    }
}
