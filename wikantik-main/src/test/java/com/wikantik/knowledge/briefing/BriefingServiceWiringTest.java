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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Wiring contract for {@link BriefingServiceWiring}: mirrors the null-safety and
 * config-gating rules of {@code BundleServiceWiring}. A null {@code pageManager}
 * or a disabled feature flag yields {@code null}; otherwise a degraded-but-live
 * service is returned even when both collaborators are null.
 */
class BriefingServiceWiringTest {

    @Test
    void nullPageManagerReturnsNull() {
        assertNull( BriefingServiceWiring.build( null, null, null, new Properties() ) );
    }

    @Test
    void disabledPropertyReturnsNull() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.briefing.enabled", "false" );
        assertNull( BriefingServiceWiring.build( null, null, mock( PageManager.class ), p ) );
    }

    @Test
    void buildsServiceWithNullCollaborators() {
        final BriefingAssemblyService svc =
            BriefingServiceWiring.build( null, null, mock( PageManager.class ), new Properties() );
        assertNotNull( svc );
        // degraded assemble still answers (empty briefing, no throw)
        final ContextBriefing b = svc.assemble( new BriefingRequest( null, null, "q", null, null ) );
        assertTrue( b.warnings().stream().anyMatch( w -> w.contains( "bundle service unavailable" ) ) );
    }
}
