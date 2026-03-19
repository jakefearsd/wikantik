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
package com.wikantik.ui;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.providers.AttachmentProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.pages.PageManager;
import com.wikantik.providers.FileSystemProvider;
import com.wikantik.util.TextUtil;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages JSPWiki installation on behalf of <code>admin/Install.jsp</code>. The contents of this class were previously part of
 * <code>Install.jsp</code>.
 *
 * @since 2.4.20
 */
public class Installer {

    public static final String ADMIN_ID = "admin";
    public static final String ADMIN_NAME = "Administrator";
    public static final String INSTALL_INFO = "Installer.Info";
    public static final String INSTALL_ERROR = "Installer.Error";
    public static final String INSTALL_WARNING = "Installer.Warning";
    public static final String APP_NAME = Engine.PROP_APPNAME;
    public static final String STORAGE_DIR = AttachmentProvider.PROP_STORAGEDIR;
    public static final String PAGE_DIR = FileSystemProvider.PROP_PAGEDIR;
    public static final String WORK_DIR = Engine.PROP_WORKDIR;
    public static final String ADMIN_GROUP = "Admin";
    public static final String PROPFILENAME = "wikantik-custom.properties" ;
    public static String TMP_DIR;
    private final Session session;
    private final File propertyFile;
    private final Properties props;
    private final Engine engine;
    private final HttpServletRequest request;
    private boolean validated;
    
    public Installer( final HttpServletRequest request, final ServletConfig config ) {
        // Get wiki session for this user
        engine = Wiki.engine().find( config );
        session = Wiki.session().find( engine, request );
        
        // Get the file for properties
        propertyFile = new File(TMP_DIR, PROPFILENAME);
        props = new Properties();
        
        // Stash the request
        this.request = request;
        validated = false;
        TMP_DIR = engine.getWikiProperties().getProperty( "wikantik.workDir" );
    }
    
    /**
     * Returns <code>true</code> if the administrative user had been created previously.
     *
     * @return the result
     */
    public boolean adminExists() {
        // See if the admin user exists already
        final UserManager userMgr = engine.getManager( UserManager.class );
        final UserDatabase userDb = userMgr.getUserDatabase();
        try {
            userDb.findByLoginName( ADMIN_ID );
            return true;
        } catch ( final NoSuchPrincipalException e ) {
            return false;
        }
    }
    
    /**
     * Creates an administrative user and returns the new password. If the admin user exists, the password will be <code>null</code>.
     *
     * @return the password
     */
    public String createAdministrator() throws WikiException {
        if ( !validated ) {
            throw new WikiSecurityException( "Cannot create administrator because one or more of the installation settings are invalid." );
        }
        
        if ( adminExists() ) {
            return null;
        }
        
        // See if the admin user exists already
        final UserManager userMgr = engine.getManager( UserManager.class );
        final UserDatabase userDb = userMgr.getUserDatabase();
        String password = null;
        
        try {
            userDb.findByLoginName( ADMIN_ID );
        } catch( final NoSuchPrincipalException e ) {
            // Create a random 12-character password
            password = TextUtil.generateRandomPassword();
            final UserProfile profile = userDb.newProfile();
            profile.setLoginName( ADMIN_ID );
            profile.setFullname( ADMIN_NAME );
            profile.setPassword( password );
            userDb.save( profile );
        }
        
        // Create a new admin group
        final GroupManager groupMgr = engine.getManager( GroupManager.class );
        Group group;
        try {
            group = groupMgr.getGroup( ADMIN_GROUP );
            group.add( new WikiPrincipal( ADMIN_NAME ) );
        } catch( final NoSuchPrincipalException e ) {
            group = groupMgr.parseGroup( ADMIN_GROUP, ADMIN_NAME, true );
        }
        groupMgr.setGroup( session, group );
        
        return password;
    }
    
    /**
     * Returns the properties as a "key=value" string separated by newlines
     * @return the string
     */
    public String getPropertiesList() {
        final Set< String > keys = props.stringPropertyNames();
        return keys.stream().map( key -> key + " = " + props.getProperty( key ) + "\n" ).collect( Collectors.joining() );
    }

    public String getPropertiesPath() {
        return propertyFile.getAbsolutePath();
    }

    /**
     * Returns a property from the Engine's properties.
     * @param key the property key
     * @return the property value
     */
    public String getProperty( final String key ) {
        return props.getProperty( key );
    }
    
    public void parseProperties () {
        final ResourceBundle rb = ResourceBundle.getBundle( InternationalizationManager.CORE_BUNDLE, session.getLocale() );
        validated = false;

        // Get application name
        String nullValue = props.getProperty( APP_NAME, rb.getString( "install.installer.default.appname" ) );
        parseProperty( APP_NAME, nullValue );

        // Get work directory
        nullValue = props.getProperty( WORK_DIR, TMP_DIR );
        parseProperty( WORK_DIR, nullValue );

        // Get page directory
        nullValue = props.getProperty( PAGE_DIR, props.getProperty( WORK_DIR, TMP_DIR ) + File.separatorChar + "data" );
        parseProperty( PAGE_DIR, nullValue );

        // Set a few more default properties, for easy setup
        props.setProperty( STORAGE_DIR, props.getProperty( PAGE_DIR ) );
        props.setProperty( PageManager.PROP_PAGEPROVIDER, "VersioningFileProvider" );
    }
    
    public void saveProperties() {
        final ResourceBundle rb = ResourceBundle.getBundle( InternationalizationManager.CORE_BUNDLE, session.getLocale() );
        // Write the file back to disk
        try {
            try( final OutputStream out = Files.newOutputStream( propertyFile.toPath() ) ) {
                props.store( out, null );
            }
            session.addMessage( INSTALL_INFO, MessageFormat.format(rb.getString("install.installer.props.saved"), propertyFile) );
        } catch( final IOException e ) {
            final Object[] args = { e.getMessage(), props.toString() };
            session.addMessage( INSTALL_ERROR, MessageFormat.format( rb.getString( "install.installer.props.notsaved" ), args ) );
        }
    }
    
    public boolean validateProperties() {
        final ResourceBundle rb = ResourceBundle.getBundle( InternationalizationManager.CORE_BUNDLE, session.getLocale() );
        session.clearMessages( INSTALL_ERROR );
        parseProperties();
        // sanitize pages, attachments and work directories
        sanitizePath( PAGE_DIR );
        sanitizePath( STORAGE_DIR );
        sanitizePath( WORK_DIR );
        validateNotNull( PAGE_DIR, rb.getString( "install.installer.validate.pagedir" ) );
        validateNotNull( APP_NAME, rb.getString( "install.installer.validate.appname" ) );
        validateNotNull( WORK_DIR, rb.getString( "install.installer.validate.workdir" ) );

        if( session.getMessages( INSTALL_ERROR ).length == 0 ) {
            validated = true;
        }
        return validated;
    }
        
    /**
     * Sets a property based on the value of an HTTP request parameter. If the parameter is not found, a default value is used instead.
     *
     * @param param the parameter containing the value we will extract
     * @param defaultValue the default to use if the parameter was not passed in the request
     */
    private void parseProperty( final String param, final String defaultValue ) {
        String value = request.getParameter( param );
        if( value == null ) {
            value = defaultValue;
        }
        props.put( param, value );
    }
    
    /**
     * Simply sanitizes any path which contains backslashes (sometimes Windows users may have them) by expanding them to double-backslashes
     *
     * @param key the key of the property to sanitize
     */
    private void sanitizePath( final String key ) {
        String s = props.getProperty( key );
        s = TextUtil.replaceString(s, "\\", "\\\\" );
        s = s.trim();
        props.put( key, s );
    }

    public void restoreUserValues() {
        desanitizePath( PAGE_DIR );
        desanitizePath( STORAGE_DIR );
        desanitizePath( WORK_DIR );
    }

    /**
     * Simply removes sanitizations so values can be shown back to the user as they were entered
     *
     * @param key the key of the property to sanitize
     */
    private void desanitizePath( final String key ) {
        String s = props.getProperty( key );
        s = TextUtil.replaceString(s, "\\\\", "\\" );
        s = s.trim();
        props.put( key, s );
    }
    
    private void validateNotNull( final String key, final String message ) {
        final String value = props.getProperty( key );
        if ( value == null || value.isEmpty() ) {
            session.addMessage( INSTALL_ERROR, message );
        }
    }
    
}
