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
package com.wikantik.audit;

import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.event.WikiSecurityEvent;

import java.security.Permission;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;

/** Translates WikiEvents into AuditEntries and records them via AuditService. */
public final class AuditEventListener implements WikiEventListener {

    /** Immutable mapping of a security-event type to its audit classification. */
    private record SecurityAudit( AuditCategory category, AuditOutcome outcome, String eventType ) {}

    /**
     * Declarative dispatch table: which {@link WikiSecurityEvent} types are audited, and how.
     * A type absent from this map is intentionally not audited (the listener records nothing).
     */
    private static final Map< Integer, SecurityAudit > SECURITY_AUDITS = Map.ofEntries(
        Map.entry( WikiSecurityEvent.LOGIN_AUTHENTICATED, new SecurityAudit( AuditCategory.AUTHN, AuditOutcome.SUCCESS, "login.ok" ) ),
        Map.entry( WikiSecurityEvent.LOGIN_FAILED,        new SecurityAudit( AuditCategory.AUTHN, AuditOutcome.FAILURE, "login.failed" ) ),
        Map.entry( WikiSecurityEvent.LOGOUT,              new SecurityAudit( AuditCategory.AUTHN, AuditOutcome.SUCCESS, "logout" ) ),
        Map.entry( WikiSecurityEvent.SESSION_EXPIRED,     new SecurityAudit( AuditCategory.AUTHN, AuditOutcome.SUCCESS, "session.expired" ) ),
        Map.entry( WikiSecurityEvent.ACCESS_DENIED,       new SecurityAudit( AuditCategory.AUTHZ, AuditOutcome.DENIED,  "access.denied" ) ),
        Map.entry( WikiSecurityEvent.GROUP_ADD,           new SecurityAudit( AuditCategory.ADMIN, AuditOutcome.SUCCESS, "group.member.add" ) ),
        Map.entry( WikiSecurityEvent.GROUP_REMOVE,        new SecurityAudit( AuditCategory.ADMIN, AuditOutcome.SUCCESS, "group.member.remove" ) ),
        Map.entry( WikiSecurityEvent.PROFILE_SAVE,        new SecurityAudit( AuditCategory.ADMIN, AuditOutcome.SUCCESS, "profile.save" ) )
    );

    /** Declarative dispatch table: which {@link WikiPageEvent} types are audited, and their event-type label. */
    private static final Map< Integer, String > PAGE_EVENT_TYPES = Map.of(
        WikiPageEvent.PAGE_DELETED, "page.delete",
        WikiPageEvent.POST_SAVE,    "page.save"
    );

    private final AuditService audit;

    public AuditEventListener( final AuditService audit ) { this.audit = audit; }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiSecurityEvent se ) {
            final AuditEntry entry = mapSecurity( se );
            if ( entry != null ) audit.record( entry );
        } else if ( event instanceof WikiPageRenameEvent re ) {
            // Must test WikiPageRenameEvent before WikiPageEvent — it's a subclass.
            final AuditEntry entry = mapRename( re );
            if ( entry != null ) audit.record( entry );
        } else if ( event instanceof WikiPageEvent pe ) {
            final AuditEntry entry = mapPage( pe );
            if ( entry != null ) audit.record( entry );
        }
    }

    private AuditEntry mapSecurity( final WikiSecurityEvent se ) {
        final SecurityAudit mapping = SECURITY_AUDITS.get( se.getType() );
        if ( mapping == null ) return null;
        final String principal = principalName( se );
        final AuditEntry.Builder b = AuditEntry.builder()
            .eventTime( Instant.now() )
            .category( mapping.category() ).eventType( mapping.eventType() ).outcome( mapping.outcome() )
            .actorPrincipal( principal )
            .actorType( principal == null ? "anonymous" : "user" );
        if ( se.getType() == WikiSecurityEvent.ACCESS_DENIED ) {
            applyPermissionTarget( b, se.getTarget() );
            final String permName = ( se.getTarget() instanceof Permission p ) ? p.getName() : null;
            final String detail = deniedDetail( permName, se.getAttributes() );
            if ( detail != null ) b.detail( detail );
        }
        enrichRequestContext( b );
        return b.build();
    }

    private AuditEntry mapPage( final WikiPageEvent pe ) {
        final String eventType = PAGE_EVENT_TYPES.get( pe.getType() );
        if ( eventType == null ) return null;
        final String pageName = pe.getPageName();
        final AuditEntry.Builder b = AuditEntry.builder()
            .eventTime( Instant.now() )
            .category( AuditCategory.CONTENT )
            .eventType( eventType )
            .outcome( AuditOutcome.SUCCESS )
            .actorType( "system" )
            .targetType( "page" )
            .targetId( pageName )
            .targetLabel( pageName );
        enrichRequestContext( b );
        return b.build();
    }

    private AuditEntry mapRename( final WikiPageRenameEvent re ) {
        final String oldName = re.getOldPageName();
        final String newName = re.getNewPageName();
        final String detail = "{\"from\":\"" + escape( oldName ) + "\",\"to\":\"" + escape( newName ) + "\"}";
        final AuditEntry.Builder b = AuditEntry.builder()
            .eventTime( Instant.now() )
            .category( AuditCategory.CONTENT )
            .eventType( "page.rename" )
            .outcome( AuditOutcome.SUCCESS )
            .actorType( "system" )
            .targetType( "page" )
            .targetId( newName )
            .targetLabel( newName )
            .detail( detail );
        enrichRequestContext( b );
        return b.build();
    }

    /** Stamps request-context columns from the request-thread MDC; no-ops to null off-thread. */
    private static void enrichRequestContext( final AuditEntry.Builder b ) {
        b.sourceIp( AuditRequestContext.sourceIp() )
         .userAgent( AuditRequestContext.userAgent() )
         .correlationId( AuditRequestContext.correlationId() );
    }

    /**
     * Maps the denied {@link Permission} (carried as the security event's target) into the
     * target columns plus a {@code detail} JSON. A null or non-Permission target leaves the
     * target columns unset (e.g. the session==null deny branch).
     */
    private void applyPermissionTarget( final AuditEntry.Builder b, final Object target ) {
        if ( !( target instanceof Permission perm ) ) return;
        final String type;
        final String id;
        final String label;
        if ( perm instanceof PagePermission pp ) {
            type = "page";  id = pp.getPage();  label = pp.getActions() + " → " + pp.getPage();
        } else if ( perm instanceof GroupPermission gp ) {
            type = "group"; id = gp.getGroup(); label = gp.getActions() + " → " + gp.getGroup();
        }
        // AllPermission.getActions() returns null, so the mapping uses fixed "*"/label values (never getActions()).
        else if ( perm instanceof AllPermission ) {
            type = "all";   id = "*";           label = "admin (AllPermission)";
        } else if ( perm instanceof WikiPermission wp ) {
            type = "wiki";  id = wp.getActions(); label = wp.getActions();
        } else {
            type = "permission"; id = perm.getName(); label = perm.getActions() + " → " + perm.getName();
        }
        b.targetType( type ).targetId( id ).targetLabel( label );
    }

    /**
     * Builds the access.denied detail JSON from the (optional) permission name, the
     * request-thread uri/method, and every entry of the event attribute map
     * ({@code reason}/{@code authStatus}/{@code roles}). Returns {@code null} when
     * nothing is available (e.g. an off-thread, attribute-less, null-permission deny).
     */
    private static String deniedDetail( final String permissionName, final Map< String, String > attributes ) {
        final StringBuilder sb = new StringBuilder( "{" );
        boolean first = true;
        if ( permissionName != null ) first = appendField( sb, first, "permission", permissionName );
        final String uri = AuditRequestContext.uri();
        final String method = AuditRequestContext.method();
        if ( uri != null )    first = appendField( sb, first, "uri", uri );
        if ( method != null ) first = appendField( sb, first, "method", method );
        if ( attributes != null ) {
            for ( final Map.Entry< String, String > en : attributes.entrySet() ) {
                if ( en.getValue() != null ) first = appendField( sb, first, en.getKey(), en.getValue() );
            }
        }
        if ( first ) return null;
        return sb.append( '}' ).toString();
    }

    /** Appends a JSON {@code "key":"value"} pair, prefixing a comma when not the first field. */
    private static boolean appendField( final StringBuilder sb, final boolean first,
                                        final String key, final String value ) {
        if ( !first ) sb.append( ',' );
        sb.append( '"' ).append( escape( key ) ).append( "\":\"" ).append( escape( value ) ).append( '"' );
        return false;
    }

    /** Minimal JSON string escaping for page names in detail JSON. */
    private static String escape( final String s ) {
        if ( s == null ) return "";
        return s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" );
    }

    /**
     * Extracts a principal name from a security event.
     * WikiSecurityEvent has two fields: {@code principal} (dedicated) and {@code target} (changed object).
     * Many callers use the 3-arg constructor {@code (src, type, target)} which leaves {@code principal=null}
     * and stores the Principal as the target — so we check both in order.
     */
    private String principalName( final WikiSecurityEvent se ) {
        final Object p = se.getPrincipal();
        if ( p instanceof Principal principal ) return principal.getName();
        final Object tgt = se.getTarget();
        if ( tgt instanceof Principal principal ) return principal.getName();
        return null;
    }
}
