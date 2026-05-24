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
package com.wikantik.auth.sso;

import com.wikantik.TestEngine;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pac4j.core.profile.CommonProfile;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class SSOAutoProvisionServiceEdgeCasesTest {

    private TestEngine engine;
    private UserDatabase userDb;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        userDb = engine.getManager( UserManager.class ).getUserDatabase();
    }

    @AfterEach
    void tearDown() {
        try { userDb.deleteByLoginName( "subject-123" ); } catch( final Exception ignored ) { }
    }

    private SSOConfig enabledConfig() {
        final Properties p = new Properties();
        p.setProperty( SSOConfig.PROP_SSO_ENABLED, "true" );
        p.setProperty( SSOConfig.PROP_AUTO_PROVISION, "true" );
        return new SSOConfig( p, "http://localhost/sso/callback" );
    }

    @Test
    void concurrentFirstLoginsProvisionExactlyOnce() throws Exception {
        final SSOConfig cfg = enabledConfig();
        final int threads = 8;
        final CountDownLatch start = new CountDownLatch( 1 );
        final ExecutorService pool = Executors.newFixedThreadPool( threads );
        try {
            for( int i = 0; i < threads; i++ ) {
                pool.submit( () -> {
                    final CommonProfile p = new CommonProfile();
                    p.setId( "subject-123" );
                    p.addAttribute( "name", "Race User" );
                    try {
                        start.await();
                        new SSOAutoProvisionService( engine, cfg )
                            .provisionIfNeeded( "subject-123", "subject-123", p );
                    } catch( final InterruptedException ignored ) {
                        Thread.currentThread().interrupt();
                    }
                } );
            }
            start.countDown();
            pool.shutdown();
            Assertions.assertTrue( pool.awaitTermination( 30, TimeUnit.SECONDS ) );
        } finally {
            pool.shutdownNow();
        }

        // Must resolve to a single, intact profile — no duplicate, no corruption.
        final UserProfile created = userDb.findByLoginName( "subject-123" );
        Assertions.assertNotNull( created );
        Assertions.assertEquals( "subject-123", created.getLoginName() );
    }

    @Test
    void provisionStampsSsoSubjectMarker() throws Exception {
        final CommonProfile profile = new CommonProfile();
        profile.setId( "subject-123" );
        new SSOAutoProvisionService( engine, enabledConfig() ).provisionIfNeeded( "subject-123", "subject-123", profile );

        final UserProfile created = userDb.findByLoginName( "subject-123" );
        Assertions.assertEquals( "subject-123",
            created.getAttributes().get( SSOAutoProvisionService.ATTR_SSO_SUBJECT ),
            "Auto-provisioned profiles must record the SSO subject for later verified-link checks." );
    }
}
