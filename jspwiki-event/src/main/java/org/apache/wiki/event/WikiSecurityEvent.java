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
package org.apache.wiki.event;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Principal;

/**
 * <p>Event class for security events: login/logout, wiki group adds/changes, and authorization decisions. When a WikiSecurityEvent
 * is constructed, the security logger {@link #LOG} is notified.</p>
 * <p>These events are logged with priority <code>ERROR</code>:</p>
 * <ul>
 *   <li>login failed - bad credential or password</li>
 * </ul>
 * <p>These events are logged with priority <code>WARN</code>:</p>
 * <ul>
 *   <li>access denied</li>
 *   <li>login failed - credential expired</li>
 *   <li>login failed - account expired</li>
 * </ul>
 * <p>These events are logged with priority <code>INFO</code>:</p>
 * <ul>
 *   <li>login succeeded</li>
 *   <li>logout</li>
 *   <li>user profile name changed</li>
 * </ul>
 * <p>These events are logged with priority <code>DEBUG</code>:</p>
 * <ul>
 *   <li>access allowed</li>
 *   <li>add group</li>
 *   <li>remove group</li>
 *   <li>clear all groups</li>
 *   <li>add group member</li>
 *   <li>remove group member</li>
 *   <li>clear all members from group</li>
 * </ul>
 * @since 2.3.79
 */
public final class WikiSecurityEvent extends WikiEvent {

    private static final long serialVersionUID    = -6751950399721334496L;

    /** When a user's attempts to log in as guest, via cookies, using a password or otherwise. */
    public static final int   LOGIN_INITIATED          = 30;
    
    /** When a user first accesses JSPWiki, but before logging in or setting a cookie. */
    public static final int   LOGIN_ANONYMOUS          = 31;
    
    /** When a user sets a cookie to assert their identity. */
    public static final int   LOGIN_ASSERTED           = 32;
    
    /** When a user authenticates with a username and password, or via container auth. */
    public static final int   LOGIN_AUTHENTICATED      = 40;

    /** When a login fails due to account expiration. */
    public static final int   LOGIN_ACCOUNT_EXPIRED    = 41;
    
    /** When a login fails due to credential expiration. */
    public static final int   LOGIN_CREDENTIAL_EXPIRED = 42;
    
    /** When a login fails due to wrong username or password. */
    public static final int   LOGIN_FAILED             = 43;
    
    /** When a user logs out. */
    public static final int   LOGOUT                   = 44;

    /** When a Principal should be added to the Session */
    public static final int PRINCIPAL_ADD               = 35;

    /** When a session expires. */
    public static final int   SESSION_EXPIRED          = 45;

    /** When a new wiki group is added. */
    public static final int   GROUP_ADD                = 46;

    /** When a wiki group is deleted. */
    public static final int   GROUP_REMOVE             = 47;

    /** When all wiki groups are removed from GroupDatabase. */
    public static final int   GROUP_CLEAR_GROUPS       = 48;

    /** When access to a resource is allowed. */
    public static final int   ACCESS_ALLOWED           = 51;
    
    /** When access to a resource is allowed. */
    public static final int   ACCESS_DENIED            = 52;
    
    /** When a user profile is saved. */
    public static final int   PROFILE_SAVE             = 53;
    
    /** When a user profile name changes. */
    public static final int   PROFILE_NAME_CHANGED     = 54;
    
    /** The security logging service. */
    private static final Logger LOG = LogManager.getLogger( "SecurityLog" );
    
    private final Principal principal;
    
    private final Object      target;

    private static final int[] ERROR_EVENTS = { LOGIN_FAILED };
    
    private static final int[] WARN_EVENTS  = { LOGIN_ACCOUNT_EXPIRED, LOGIN_CREDENTIAL_EXPIRED };
    
    private static final int[] INFO_EVENTS  = { LOGIN_AUTHENTICATED, SESSION_EXPIRED, LOGOUT, PROFILE_NAME_CHANGED };
    
    /**
     * Constructs a new instance of this event type, which signals a security event has occurred. The <code>source</code> parameter is
     * required, and may not be <code>null</code>. When the WikiSecurityEvent is constructed, the security logger {@link #LOG} is notified.
     *
     * @param src the source of the event, which can be any object: a wiki page, group or authentication/authentication/group manager.
     * @param type the type of event
     * @param principal the subject of the event, which may be <code>null</code>
     * @param target the changed Object, which may be <code>null</code>
     */
    public WikiSecurityEvent( final Object src, final int type, final Principal principal, final Object target ) {
        super( src, type );
        if( src == null ) {
            throw new IllegalArgumentException( "Argument(s) cannot be null." );
        }
        this.principal = principal;
        this.target = target;
        if( LOG.isEnabled( Level.ERROR ) && ArrayUtils.contains( ERROR_EVENTS, type ) ) {
            LOG.error( this );
        } else if( LOG.isEnabled( Level.WARN ) && ArrayUtils.contains( WARN_EVENTS, type ) ) {
            LOG.warn( this );
        } else if( LOG.isEnabled( Level.INFO ) && ArrayUtils.contains( INFO_EVENTS, type ) ) {
            LOG.info( this );
        }
        LOG.debug( this );
    }

    /**
     * Constructs a new instance of this event type, which signals a security event has occurred. The <code>source</code> parameter
     * is required, and may not be <code>null</code>. When the WikiSecurityEvent is constructed, the security logger {@link #LOG}
     * is notified.
     *
     * @param src the source of the event, which can be any object: a wiki page, group or authentication/authentication/group manager.
     * @param type the type of event
     * @param target the changed Object, which may be <code>null</code>.
     */
    public WikiSecurityEvent( final Object src, final int type, final Object target ) {
        this( src, type, null, target );
    }

    /**
     * Returns the principal to whom the operation applied, if supplied. This method may return <code>null</code>
     * <em>&#8212; and calling methods should check for this condition</em>.
     *
     * @return the changed object
     */
    public Object getPrincipal() {
        return principal;
    }
    
    /**
     * Returns the object that was operated on, if supplied. This method may return <code>null</code>
     * <em>&#8212; and calling methods should check for this condition</em>.
     *
     * @return the changed object
     */
    public Object getTarget() {
        return target;
    }

    /**
     * Prints a String (human-readable) representation of this object.
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final StringBuilder msg = new StringBuilder();
        msg.append( "WikiSecurityEvent." );
        msg.append(  eventName( getType() ) );
        final Object obj = getSrc(); // cfr. https://forums.oracle.com/forums/thread.jspa?threadID=1184115
        msg.append( " [source=" ).append( obj.toString() );
        if( principal != null ) {
            msg.append( ", principal=" ).append( principal.getClass().getName() );
            msg.append( " " ).append( principal.getName() );
        }
        msg.append( ", target=" ).append( target );
        msg.append( "]" );
        return msg.toString();
    }
    
    /**
     * Returns a textual representation of an event type.
     *
     * @param type the type
     * @return the string representation
     */
    public String eventName( final int type ) {
        return switch( type ) {
            case LOGIN_AUTHENTICATED       -> "LOGIN_AUTHENTICATED";
            case LOGIN_ACCOUNT_EXPIRED     -> "LOGIN_ACCOUNT_EXPIRED";
            case LOGIN_CREDENTIAL_EXPIRED  -> "LOGIN_ACCOUNT_EXPIRED";
            case LOGIN_FAILED              -> "LOGIN_FAILED";
            case LOGOUT                    -> "LOGOUT";
            case PRINCIPAL_ADD             -> "PRINCIPAL_ADD";
            case SESSION_EXPIRED           -> "SESSION_EXPIRED";
            case GROUP_ADD                 -> "GROUP_ADD";
            case GROUP_REMOVE              -> "GROUP_REMOVE";
            case GROUP_CLEAR_GROUPS        -> "GROUP_CLEAR_GROUPS";
            case ACCESS_ALLOWED            -> "ACCESS_ALLOWED";
            case ACCESS_DENIED             -> "ACCESS_DENIED";
            case PROFILE_NAME_CHANGED      -> "PROFILE_NAME_CHANGED";
            case PROFILE_SAVE              -> "PROFILE_SAVE";
            default                        -> super.eventName();
        };
    }

    /**
     *  Returns a human-readable description of the event type.
     *
     * @return a String description of the type
     */
    @Override
    public String getTypeDescription() {
        return switch ( getType() ) {
            case LOGIN_AUTHENTICATED       -> "login authenticated";
            case LOGIN_ACCOUNT_EXPIRED     -> "login failed: expired account";
            case LOGIN_CREDENTIAL_EXPIRED  -> "login failed: credential expired";
            case LOGIN_FAILED              -> "login failed";
            case LOGOUT                    -> "user logged out";
            case PRINCIPAL_ADD             -> "new principal added";
            case SESSION_EXPIRED           -> "session expired";
            case GROUP_ADD                 -> "new group added";
            case GROUP_REMOVE              -> "group removed";
            case GROUP_CLEAR_GROUPS        -> "all groups cleared";
            case ACCESS_ALLOWED            -> "access allowed";
            case ACCESS_DENIED             -> "access denied";
            case PROFILE_NAME_CHANGED      -> "user profile name changed";
            case PROFILE_SAVE              -> "user profile saved";
            default                        -> super.getTypeDescription();
        };
    }

}
