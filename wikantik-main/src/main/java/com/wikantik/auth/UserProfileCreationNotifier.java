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
package com.wikantik.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.util.HttpUtil;
import com.wikantik.util.MailUtil;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import java.util.Locale;

/**
 * Sends the welcome (to the new user) and admin-notification e-mails after a new
 * user profile is created.
 *
 * <p>Extracted from {@link DefaultUserManager} so that class's structural-complexity
 * metrics (WMC/ATFD) stay within the design-quality gate — this collaborator owns
 * the post-creation notification e-mails exclusively.</p>
 */
final class UserProfileCreationNotifier {

    private static final Logger LOG = LogManager.getLogger( UserProfileCreationNotifier.class );

    private final Engine engine;

    UserProfileCreationNotifier( final Engine engine ) {
        this.engine = engine;
    }

    /** Sends the welcome e-mail (if the profile supplied an address) and the admin notification (if configured). */
    void notifyProfileCreated( final Context context, final UserProfile profile ) {
        sendWelcomeEmail( context, profile );
        sendAdminNotification( context, profile );
    }

    private void sendWelcomeEmail( final Context context, final UserProfile profile ) {
        if ( profile.getEmail() == null ) {
            return;
        }
        final Locale loc = context.getWikiSession().getLocale();
        try {
            final InternationalizationManager i18n = CoreSubsystemBridge.fromLegacyEngine( engine ).i18n();
            final String app = engine.getApplicationName();
            final String to = profile.getEmail();
            final String subject = i18n.get( InternationalizationManager.CORE_BUNDLE, loc,
                                             "notification.createUserProfile.accept.subject", app );

            final String loginUrl = engine.getURL( ContextEnum.WIKI_LOGIN.getRequestContext(), null, null );
            final String absoluteLoginUrl = HttpUtil.getAbsoluteUrl( context.getHttpRequest(), loginUrl );

            final String content = i18n.get( InternationalizationManager.CORE_BUNDLE, loc,
                                             "notification.createUserProfile.accept.content", app,
                                             profile.getLoginName(),
                                             profile.getFullname(),
                                             profile.getEmail(),
                                             absoluteLoginUrl );
            MailUtil.sendMessage( CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(), to, subject, content );
        } catch ( final AddressException e ) {
            LOG.debug( e.getMessage(), e );
        } catch ( final MessagingException me ) {
            LOG.error( "Could not send registration confirmation e-mail. Is the e-mail server running?", me );
        }
    }

    private void sendAdminNotification( final Context context, final UserProfile profile ) {
        final String adminEmail = CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties().getProperty( "wikantik.admin.notification.email" );
        if ( adminEmail == null || adminEmail.isBlank() ) {
            return;
        }
        final Locale loc = context.getWikiSession().getLocale();
        try {
            final InternationalizationManager i18n = CoreSubsystemBridge.fromLegacyEngine( engine ).i18n();
            final String app = engine.getApplicationName();
            final String adminSubject = i18n.get( InternationalizationManager.CORE_BUNDLE, loc,
                    "notification.createUserProfile.admin.subject", app );
            final String adminContent = i18n.get( InternationalizationManager.CORE_BUNDLE, loc,
                    "notification.createUserProfile.admin.content", app,
                    profile.getLoginName(),
                    profile.getFullname(),
                    profile.getEmail() );
            MailUtil.sendMessage( CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(), adminEmail, adminSubject, adminContent );
        } catch ( final AddressException e ) {
            LOG.debug( e.getMessage(), e );
        } catch ( final MessagingException me ) {
            LOG.error( "Could not send admin notification e-mail. Is the e-mail server running?", me );
        }
    }
}
