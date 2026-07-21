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

import com.wikantik.api.core.Session;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The per-thread static guest session must be bound to the CALLER's engine.
 * The ThreadLocal used to cache the first guest created on a thread and then
 * serve it for any engine — so a test (or embedded host) running two engines
 * on one thread got a guest whose managers belonged to the other, possibly
 * stopped, engine. Symptoms: order-dependent flakes where permission filters
 * silently drop results (PluginCoverageTest search rows) or session lookups
 * blow up on a null engine (SearchResourceQueryLogTest).
 */
class WikiSessionStaticGuestTest {

    @Test
    void staticGuestIsRebuiltWhenEngineChanges() throws Exception {
        final TestEngine e1 = new TestEngine( TestEngine.getTestProperties() );
        try {
            final Session s1a = WikiSession.getWikiSession( e1, null );
            final Session s1b = WikiSession.getWikiSession( e1, null );
            Assertions.assertSame( s1a, s1b, "same engine on the same thread must reuse the cached guest" );

            final TestEngine e2 = new TestEngine( TestEngine.getTestProperties() );
            try {
                final Session s2 = WikiSession.getWikiSession( e2, null );
                Assertions.assertNotSame( s1a, s2,
                        "a different engine must NOT be served the previous engine's cached guest" );

                final Session s1c = WikiSession.getWikiSession( e1, null );
                Assertions.assertNotSame( s2, s1c,
                        "switching back must rebind to the requested engine again" );
            } finally {
                e2.stop();
            }
        } finally {
            e1.stop();
        }
    }
}
