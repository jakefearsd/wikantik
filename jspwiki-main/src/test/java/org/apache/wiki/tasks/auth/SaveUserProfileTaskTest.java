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
package org.apache.wiki.tasks.auth;

import org.apache.wiki.HttpMockFactory;
import org.apache.wiki.TestEngine;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.auth.UserManager;
import org.apache.wiki.auth.user.UserDatabase;
import org.apache.wiki.auth.user.UserProfile;
import org.apache.wiki.util.MailUtil;
import org.apache.wiki.workflow.Outcome;
import org.apache.wiki.workflow.WorkflowManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Locale;
import java.util.Properties;

import static org.apache.wiki.TestEngine.with;

class SaveUserProfileTaskTest {

    TestEngine engine;

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            // Clean up any test profiles we created
            final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
            for ( final String login : new String[]{ "testuser", "testuser2" } ) {
                try {
                    db.deleteByLoginName( login );
                } catch ( final Exception e ) {
                    // ignore if not found
                }
            }
            engine.stop();
        }
    }

    @Test
    void testAdminNotificationSentWhenPropertyConfigured() throws Exception {
        engine = TestEngine.build( with( "jspwiki.admin.notification.email", "admin@example.com" ) );
        final UserProfile profile = engine.getManager( UserManager.class ).getUserDatabase().newProfile();
        profile.setLoginName( "testuser" );
        profile.setFullname( "Test User" );
        profile.setEmail( "testuser@example.com" );
        profile.setPassword( "password" );

        final Page page = Wiki.contents().page( engine, "TestPage" );
        final Context context = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(), page );

        final SaveUserProfileTask task = new SaveUserProfileTask( Locale.ENGLISH );
        task.setWorkflow( 1, new HashMap<>() );
        task.getWorkflowContext().put( WorkflowManager.WF_UP_CREATE_SAVE_ATTR_SAVED_PROFILE, profile );

        try ( final MockedStatic< MailUtil > mailUtilMock = Mockito.mockStatic( MailUtil.class ) ) {
            final Outcome result = task.execute( context );

            Assertions.assertEquals( Outcome.STEP_COMPLETE, result );

            // Verify user confirmation email was attempted
            mailUtilMock.verify( () -> MailUtil.sendMessage(
                    Mockito.any( Properties.class ),
                    Mockito.eq( "testuser@example.com" ),
                    Mockito.anyString(),
                    Mockito.anyString()
            ) );

            // Verify admin notification email was attempted
            mailUtilMock.verify( () -> MailUtil.sendMessage(
                    Mockito.any( Properties.class ),
                    Mockito.eq( "admin@example.com" ),
                    Mockito.anyString(),
                    Mockito.anyString()
            ) );
        }
    }

    @Test
    void testNoAdminNotificationWhenPropertyNotConfigured() throws Exception {
        engine = TestEngine.build();
        final UserProfile profile = engine.getManager( UserManager.class ).getUserDatabase().newProfile();
        profile.setLoginName( "testuser2" );
        profile.setFullname( "Test User 2" );
        profile.setEmail( "testuser2@example.com" );
        profile.setPassword( "password" );

        final Page page = Wiki.contents().page( engine, "TestPage" );
        final Context context = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(), page );

        final SaveUserProfileTask task = new SaveUserProfileTask( Locale.ENGLISH );
        task.setWorkflow( 1, new HashMap<>() );
        task.getWorkflowContext().put( WorkflowManager.WF_UP_CREATE_SAVE_ATTR_SAVED_PROFILE, profile );

        try ( final MockedStatic< MailUtil > mailUtilMock = Mockito.mockStatic( MailUtil.class ) ) {
            final Outcome result = task.execute( context );

            Assertions.assertEquals( Outcome.STEP_COMPLETE, result );

            // Verify user confirmation email was attempted
            mailUtilMock.verify( () -> MailUtil.sendMessage(
                    Mockito.any( Properties.class ),
                    Mockito.eq( "testuser2@example.com" ),
                    Mockito.anyString(),
                    Mockito.anyString()
            ) );

            // Verify admin notification was NOT sent (only 1 call total - the user email)
            mailUtilMock.verify( () -> MailUtil.sendMessage(
                    Mockito.any( Properties.class ),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anyString()
            ), Mockito.times( 1 ) );
        }
    }
}
