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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.validate.PasswordValidator;
import com.wikantik.auth.user.DuplicateUserException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.filters.FilterManager;
import com.wikantik.filters.SpamFilter;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.preferences.Preferences;
import com.wikantik.ui.InputValidator;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;
import com.wikantik.util.TextUtil;

import jakarta.servlet.http.HttpServletRequest;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Supplier;

/**
 * Validates a {@link UserProfile} for save — spam-filter check, required-field
 * validation, password strength/confirmation, and login-name/full-name/email
 * uniqueness.
 *
 * <p>Extracted from {@link DefaultUserManager} so that class's structural-complexity
 * metrics (WMC/ATFD) stay within the design-quality gate — this collaborator owns
 * the {@code validateProfile} pipeline exclusively.</p>
 */
final class UserProfileValidator {

    private static final Logger LOG = LogManager.getLogger( UserProfileValidator.class );
    private static final String SESSION_MESSAGES = "profile";

    private final Engine engine;
    private final Supplier< UserDatabase > userDatabase;

    UserProfileValidator( final Engine engine, final Supplier< UserDatabase > userDatabase ) {
        this.engine = engine;
        this.userDatabase = userDatabase;
    }

    /** Checks that no other profile already claims this login name or full name; throws on conflict. */
    void assertNoDuplicate( final UserProfile profile, final UserProfile oldProfile ) throws DuplicateUserException {
        UserProfile otherProfile;
        try {
            otherProfile = userDatabase.get().findByLoginName( profile.getLoginName() );
            if( otherProfile != null && !otherProfile.equals( oldProfile ) ) {
                throw new DuplicateUserException( "security.error.login.taken", profile.getLoginName() );
            }
        } catch( final NoSuchPrincipalException e ) {
            LOG.debug( "Login name '{}' is available (no existing profile found)", profile.getLoginName() );
        }
        try {
            otherProfile = userDatabase.get().findByFullName( profile.getFullname() );
            if( otherProfile != null && !otherProfile.equals( oldProfile ) ) {
                throw new DuplicateUserException( "security.error.fullname.taken", profile.getFullname() );
            }
        } catch( final NoSuchPrincipalException e ) {
            LOG.debug( "Full name '{}' is available (no existing profile found)", profile.getFullname() );
        }
    }

    void validate( final Context context, final UserProfile profile ) {
        final Session session = context.getWikiSession();
        final ResourceBundle rb = Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE );

        if( validateSpamFilter( context, session, profile ) ) {
            return;
        }
        validateRequiredFields( context, session, profile, rb );
        validatePassword( context, session, profile, rb );
        validateUniqueness( session, profile, rb );
    }

    /** Returns {@code true} if the spam filter rejected the profile (caller should stop further validation). */
    private boolean validateSpamFilter( final Context context, final Session session, final UserProfile profile ) {
        final FilterManager fm = RenderingSubsystemBridge.fromLegacyEngine( engine ).filterManager();
        final boolean spamFilterRejects = fm.getFilterList().stream()
                .filter( SpamFilter.class::isInstance )
                .map( SpamFilter.class::cast )
                .findFirst()
                .map( spamFilter -> !spamFilter.isValidUserProfile( context, profile ) )
                .orElse( false );
        if( spamFilterRejects ) {
            session.addMessage( SESSION_MESSAGES, "Invalid userprofile" );
            return true;
        }
        return false;
    }

    private void validateRequiredFields( final Context context, final Session session, final UserProfile profile, final ResourceBundle rb ) {
        // If container-managed auth and user not logged in, throw an error
        if ( com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).authentication().isContainerAuthenticated()
             && !context.getWikiSession().isAuthenticated() ) {
            session.addMessage( SESSION_MESSAGES, rb.getString("security.error.createprofilebeforelogin") );
        }

        final InputValidator validator = new InputValidator( SESSION_MESSAGES, context );
        validator.validateNotNull( profile.getLoginName(), rb.getString("security.user.loginname") );
        validator.validateNotNull( profile.getFullname(), rb.getString("security.user.fullname") );
        validator.validate( profile.getEmail(), rb.getString("security.user.email"), InputValidator.EMAIL );
    }

    private void validatePassword( final Context context, final Session session, final UserProfile profile, final ResourceBundle rb ) {
        if( com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).authentication().isContainerAuthenticated() ) {
            return;
        }

        // passwords must match and can't be null
        final String password = profile.getPassword();
        if( password == null ) {
            session.addMessage( SESSION_MESSAGES, rb.getString( "security.error.blankpassword" ) );
            return;
        }

        reportPasswordStrengthErrors( session, password, rb );
        checkPasswordConfirmation( context, session, profile, password, rb );
    }

    /** Runs NIST 800-63B password-strength validation, adding a session message for each violation. */
    private void reportPasswordStrengthErrors( final Session session, final String password, final ResourceBundle rb ) {
        final List<String> passwordErrors = PasswordValidator.validate( password, CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties() );
        for ( final String key : passwordErrors ) {
            if ( key.contains( "{0}" ) || PasswordValidator.KEY_TOO_SHORT.equals( key ) || PasswordValidator.KEY_TOO_LONG.equals( key ) ) {
                final int limit = PasswordValidator.KEY_TOO_SHORT.equals( key )
                        ? TextUtil.getIntegerProperty( CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(), PasswordValidator.PROP_MIN_LENGTH, PasswordValidator.DEFAULT_MIN_LENGTH )
                        : TextUtil.getIntegerProperty( CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(), PasswordValidator.PROP_MAX_LENGTH, PasswordValidator.DEFAULT_MAX_LENGTH );
                session.addMessage( SESSION_MESSAGES, MessageFormat.format( rb.getString( key ), limit ) );
            } else {
                session.addMessage( SESSION_MESSAGES, rb.getString( key ) );
            }
        }
    }

    /** Verifies the confirmation field matches and, for existing accounts, the current password. */
    private void checkPasswordConfirmation( final Context context, final Session session, final UserProfile profile,
                                             final String password, final ResourceBundle rb ) {
        final HttpServletRequest request = context.getHttpRequest();
        final String password0 = ( request == null ) ? null : request.getParameter( "password0" );
        final String password2 = ( request == null ) ? null : request.getParameter( "password2" );
        if( !password.equals( password2 ) ) {
            session.addMessage( SESSION_MESSAGES, rb.getString( "security.error.passwordnomatch" ) );
        }
        if( !profile.isNew() && !userDatabase.get().validatePassword( profile.getLoginName(), password0 ) ) {
            session.addMessage( SESSION_MESSAGES, rb.getString( "security.error.passwordnomatch" ) );
        }
    }

    private void validateUniqueness( final Session session, final UserProfile profile, final ResourceBundle rb ) {
        final String fullName = profile.getFullname();
        final String loginName = profile.getLoginName();
        final String email = profile.getEmail();

        // It's illegal to use as a full name someone else's login name
        try {
            final UserProfile otherProfile = userDatabase.get().find( fullName );
            if( otherProfile != null && !profile.equals( otherProfile ) && !fullName.equals( otherProfile.getFullname() ) ) {
                final Object[] args = { fullName };
                session.addMessage( SESSION_MESSAGES, MessageFormat.format( rb.getString( "security.error.illegalfullname" ), args ) );
            }
        } catch( final NoSuchPrincipalException e ) {
            LOG.debug( "Full name '{}' does not collide with an existing login name", fullName );
        }

        // It's illegal to use as a login name someone else's full name
        try {
            final UserProfile otherProfile = userDatabase.get().find( loginName );
            if( otherProfile != null && !profile.equals( otherProfile ) && !loginName.equals( otherProfile.getLoginName() ) ) {
                final Object[] args = { loginName };
                session.addMessage( SESSION_MESSAGES, MessageFormat.format( rb.getString( "security.error.illegalloginname" ), args ) );
            }
        } catch( final NoSuchPrincipalException e ) {
            LOG.debug( "Login name '{}' does not collide with an existing full name", loginName );
        }

        // It's illegal to use multiple accounts with the same email
        try {
            final UserProfile otherProfile = userDatabase.get().findByEmail( email );
            if( otherProfile != null && !profile.getUid().equals( otherProfile.getUid() ) // Issue JSPWIKI-1042
                    && !profile.equals( otherProfile ) && StringUtils.lowerCase( email )
                    .equals( StringUtils.lowerCase( otherProfile.getEmail() ) ) ) {
                final Object[] args = { email };
                session.addMessage( SESSION_MESSAGES, MessageFormat.format( rb.getString( "security.error.email.taken" ), args ) );
            }
        } catch( final NoSuchPrincipalException e ) {
            LOG.debug( "Email '{}' is not in use by another account", email );
        }
    }
}
