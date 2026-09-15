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
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.auth.user.DummyUserDatabase;
import com.wikantik.auth.user.DuplicateUserException;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.ui.InputValidator;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.TextUtil;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Permission;
import java.security.Principal;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.WeakHashMap;


/**
 * Default implementation for {@link UserManager}.
 *
 * @since 2.3
 */
public class DefaultUserManager implements UserManager {

    private static final String USERDATABASE_PACKAGE = "com.wikantik.auth.user";
    private static final String SESSION_MESSAGES = "profile";
    private static final String PARAM_EMAIL = "email";
    private static final String PARAM_FULLNAME = "fullname";
    private static final String PARAM_PASSWORD = "password";
    private static final String PARAM_LOGINNAME = "loginname";
    private static final String UNKNOWN_CLASS = "<unknown>";

    private Engine engine;

    private static final Logger LOG = LogManager.getLogger( DefaultUserManager.class);

    /** Associates wiki sessions with profiles */
    private final Map< Session, UserProfile > profiles = new WeakHashMap<>();

    /** The user database loads, manages and persists user identities */
    private UserDatabase database;

    /** Validates profiles on save — extracted collaborator, see {@link UserProfileValidator}. */
    private UserProfileValidator profileValidator;

    /** Sends creation notification e-mails — extracted collaborator, see {@link UserProfileCreationNotifier}. */
    private UserProfileCreationNotifier profileCreationNotifier;

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine engine, final Properties props ) {
        this.engine = engine;

        // Eagerly initialize the user database on the main thread to ensure
        // JNDI context is available (required for JDBCUserDatabase).
        //
        // Without this eager initialization, the following call chain can trigger
        // getUserDatabase() from a background thread that lacks JNDI context:
        //   ReferenceManager (background thread)
        //     -> scanWikiLinks()
        //       -> RenderingManager.textToHTML()
        //         -> MarkdownParser constructor
        //           -> getUserDatabase()  <-- JNDI lookup fails here
        //
        // By initializing here on the main servlet thread, the database connection
        // is established before any background threads attempt to use it.
        getUserDatabase();

        // Attach the PageManager as a listener
        // TODO: it would be better if we did this in PageManager directly
        addWikiEventListener( PageSubsystemBridge.fromLegacyEngine( engine ).pages() );
    }

    /** {@inheritDoc} */
    @Override
    public UserDatabase getUserDatabase() {
        if( database != null ) {
            return database;
        }

        String dbClassName = UNKNOWN_CLASS;

        try {
            dbClassName = TextUtil.getRequiredProperty( CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(), PROP_DATABASE );

            LOG.info( "Attempting to load user database class {}", dbClassName );
            database = ClassUtil.buildInstance( USERDATABASE_PACKAGE, dbClassName );
            database.initialize( engine, CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties() );
            LOG.info( "UserDatabase initialized." );
        } catch( final NoSuchElementException | NoRequiredPropertyException e ) {
            LOG.error( "You have not set the '{}'. You need to do this if you want to enable user management by JSPWiki.", PROP_DATABASE, e );
        } catch( final ReflectiveOperationException e ) {
            LOG.error( "UserDatabase {} cannot be instantiated", dbClassName, e );
        } catch( final WikiSecurityException e ) {
            LOG.error( "Exception initializing user database: {}", e.getMessage(), e );
        } finally {
            if( database == null ) {
                LOG.info( "I could not create a database object you specified (or didn't specify), so I am falling back to a default." );
                database = new DummyUserDatabase();
            }
        }

        return database;
    }

    /** {@inheritDoc} */
    @Override
    public UserProfile getUserProfile( final Session session ) {
        // Look up cached user profile
        UserProfile profile = profiles.get( session );
        boolean newProfile = profile == null;
        Principal user = null;

        // If user is authenticated, figure out if this is an existing profile
        if ( session.isAuthenticated() ) {
            user = session.getUserPrincipal();
            try {
                profile = getUserDatabase().find( user.getName() );
                newProfile = false;
            } catch( final NoSuchPrincipalException e ) {
                LOG.debug( "No stored profile for authenticated principal '{}' — will create a new one", user.getName() );
            }
        }

        if ( newProfile ) {
            profile = getUserDatabase().newProfile();
            if ( user != null ) {
                profile.setLoginName( user.getName() );
            }
            if ( !profile.isNew() ) {
                throw new IllegalStateException( "New profile should be marked 'new'. Check your UserProfile implementation." );
            }
        }

        // Stash the profile for next time
        profiles.put( session, profile );
        return profile;
    }

    /** {@inheritDoc} */
    @Override
    public void setUserProfile( final Context context, final UserProfile profile ) throws DuplicateUserException, WikiException {
        final Session session = context.getWikiSession();
        checkEditProfilePermission( session );

        // Check if profile is new, and see if container allows creation
        final boolean newProfile = profile.isNew();

        // Check if another user profile already has the fullname or loginname
        final UserProfile oldProfile = getUserProfile( session );
        final boolean nameChanged = ( oldProfile != null && oldProfile.getFullname() != null ) &&
                                    !( oldProfile.getFullname().equals( profile.getFullname() ) &&
                                    oldProfile.getLoginName().equals( profile.getLoginName() ) );
        profileValidator().assertNoDuplicate( profile, oldProfile );

        // For new accounts, create approval workflow for user profile save.
        if( newProfile && oldProfile != null && oldProfile.isNew() ) {
            saveNewProfile( context, session, profile );
        } else { // For existing accounts, just save the profile
            saveExistingProfile( session, profile, oldProfile, nameChanged );
        }
    }

    /** Verifies the current session may save a wiki profile. */
    private void checkEditProfilePermission( final Session session ) throws WikiSecurityException {
        final Permission editProfilePermission = new WikiPermission( engine.getApplicationName(), WikiPermission.EDIT_PROFILE_ACTION );
        if ( !com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).authorization().checkPermission( session, editProfilePermission ) ) {
            throw new WikiSecurityException( "You are not allowed to save wiki profiles." );
        }
    }

    /** Starts the approval workflow for a brand-new account, logging the user in when no approval is needed. */
    private void saveNewProfile( final Context context, final Session session, final UserProfile profile )
            throws WikiException {
        startUserProfileCreationWorkflow( context, profile );

        // If the profile doesn't need approval, then just log the user in
        try {
            final AuthenticationManager mgr = com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).authentication();
            if( !mgr.isContainerAuthenticated() ) {
                mgr.login( session, null, profile.getLoginName(), profile.getPassword() );
            }
        } catch( final WikiException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }

        // Alert all listeners that the profile changed...
        // ...this will cause credentials to be reloaded in the wiki session
        fireEvent( WikiSecurityEvent.PROFILE_SAVE, session, profile );
    }

    /** Saves an existing account's profile, renaming the login first if it changed. */
    private void saveExistingProfile( final Session session, final UserProfile profile, final UserProfile oldProfile,
                                       final boolean nameChanged ) throws WikiException {
        // If login name changed, rename it first
        if( nameChanged && !oldProfile.getLoginName().equals( profile.getLoginName() ) ) {
            getUserDatabase().rename( oldProfile.getLoginName(), profile.getLoginName() );
        }

        // Now, save the profile (userdatabase will take care of timestamps for us)
        getUserDatabase().save( profile );

        if( nameChanged ) {
            // Fire an event if the login name or full name changed
            final UserProfile[] profiles = { oldProfile, profile };
            fireEvent( WikiSecurityEvent.PROFILE_NAME_CHANGED, session, profiles );
        } else {
            // Fire an event that says we have new a new profile (new principals)
            fireEvent( WikiSecurityEvent.PROFILE_SAVE, session, profile );
        }
    }

    /** {@inheritDoc} */
    @Override
    public void startUserProfileCreationWorkflow( final Context context, final UserProfile profile ) throws WikiException {
        // Save the profile directly (userdatabase will take care of timestamps)
        getUserDatabase().save( profile );
        profileCreationNotifier().notifyProfileCreated( context, profile );
    }

    /** Lazily builds the creation notifier — {@link #engine} isn't guaranteed set until {@link #initialize}. */
    private UserProfileCreationNotifier profileCreationNotifier() {
        if ( profileCreationNotifier == null ) {
            profileCreationNotifier = new UserProfileCreationNotifier( engine );
        }
        return profileCreationNotifier;
    }

    /** {@inheritDoc} */
    @Override
    public UserProfile parseProfile( final Context context ) {
        // Retrieve the user's profile (may have been previously cached)
        final UserProfile profile = getUserProfile( context.getWikiSession() );
        final HttpServletRequest request = context.getHttpRequest();

        // Extract values from request stream (cleanse whitespace as needed)
        String loginName = request.getParameter( PARAM_LOGINNAME );
        String password = request.getParameter( PARAM_PASSWORD );
        String fullname = request.getParameter( PARAM_FULLNAME );
        String email = request.getParameter( PARAM_EMAIL );
        loginName = StringUtils.trim( loginName );
        password = InputValidator.isBlank( password ) ? null : password;
        fullname = StringUtils.trim( fullname );
        email = StringUtils.trim( email );

        // A special case if we have container authentication: if authenticated, login name is always taken from container
        if ( com.wikantik.auth.subsystem.AuthSubsystemBridge.fromLegacyEngine( engine ).authentication().isContainerAuthenticated() && context.getWikiSession().isAuthenticated() ) {
            loginName = context.getWikiSession().getLoginPrincipal().getName();
        }

        // Set the profile fields!
        profile.setLoginName( loginName );
        profile.setEmail( email );
        profile.setFullname( fullname );
        profile.setPassword( password );
        return profile;
    }

    /** {@inheritDoc} */
    @Override
    public void validateProfile( final Context context, final UserProfile profile ) {
        profileValidator().validate( context, profile );
    }

    /** Lazily builds the profile validator — {@link #engine} isn't guaranteed set until {@link #initialize}. */
    private UserProfileValidator profileValidator() {
        if ( profileValidator == null ) {
            profileValidator = new UserProfileValidator( engine, this::getUserDatabase );
        }
        return profileValidator;
    }

    /** {@inheritDoc} */
    @Override
    public Principal[] listWikiNames() throws WikiSecurityException {
        return getUserDatabase().getWikiNames();
    }

    // events processing .......................................................

    /**
     * Registers a WikiEventListener with this instance.
     * This is a convenience method.
     * @param listener the event listener
     */
    @Override public synchronized void addWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.addWikiEventListener( this, listener );
    }

    /**
     * Un-registers a WikiEventListener with this instance.
     * This is a convenience method.
     * @param listener the event listener
     */
    @Override public synchronized void removeWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.removeWikiEventListener( this, listener );
    }

}
