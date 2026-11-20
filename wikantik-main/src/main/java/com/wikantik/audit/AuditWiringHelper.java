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

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.LastLoginEventListener;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.content.PageRenamer;
import com.wikantik.event.WikiEventManager;
import com.wikantik.filters.FilterManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the audit subsystem (JDBC repo + async writer, read policy) and attaches the audit and last-login
 * listeners. Never calls {@code getManager}: the engine resolves the managers and passes them in, so the
 * per-method getManager freeze in DecompositionArchTest stays with the engine.
 */
public final class AuditWiringHelper {

    private static final Logger LOG = LogManager.getLogger( AuditWiringHelper.class );

    private static final int QUEUE_CAPACITY = 10_000;

    private AuditWiringHelper() {}

    /**
     * Strong-reference holder: WikiEventManager keeps listeners in a WeakHashMap, so the listeners must stay
     * reachable for the engine's lifetime. {@code lastLoginListener} is null when no user database is available.
     */
    public record Wired( AuditService service, AuditWriterThread writer, AuditReadPolicy readPolicy,
                         AuditEventListener listener, LastLoginEventListener lastLoginListener ) {}

    /** Managers whose events the audit chain observes; any may be null (listener simply not attached). */
    public record Managers( PageManager pageManager, StructuralIndexService structuralIndex,
                            AuthenticationManager authn, AuthorizationManager authz, UserManager user,
                            GroupManager group, PageRenamer pageRenamer, FilterManager filterManager ) {}

    /** @return the wired holder, or null when there is no datasource (audit disabled) */
    public static Wired wireAudit( final Properties props, final DataSource ds, final Managers m ) {
        if ( ds == null ) {
            LOG.warn( "Audit subsystem disabled — no JNDI DataSource resolved; "
                    + "security and page events will not be written to the audit log." );
            return null;
        }
        final AuditSubsystemFactory.AuditSubsystem sub = AuditSubsystemFactory.build( ds, QUEUE_CAPACITY );
        final AuditReadPolicy readPolicy = new AuditReadPolicy(
            frontmatterLookup( m.pageManager() ),
            clusterLookup( m.structuralIndex() ),
            parseAuditedClusters( props.getProperty( "wikantik.audit.readClusters", "" ) ) );
        final AuditEventListener listener = new AuditEventListener( sub.service() );
        final LastLoginEventListener lastLogin = attachAuthListeners( listener, m );
        attachContentListeners( listener, m );
        LOG.info( "Audit subsystem initialized (queue={})", QUEUE_CAPACITY );
        return new Wired( sub.service(), sub.writer(), readPolicy, listener, lastLogin );
    }

    /** Frontmatter-by-page-name lookup; never throws, returns an empty map on any failure. */
    private static Function< String, Map< String, Object > > frontmatterLookup( final PageManager pm ) {
        return pageName -> {
            try {
                if ( pm == null ) return Map.of();
                final String raw = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
                if ( raw == null || raw.isEmpty() ) return Map.of();
                final ParsedPage parsed = FrontmatterParser.parse( raw );
                final Map< String, Object > fm = parsed.metadata();
                return fm != null ? fm : Map.of();
            } catch ( final Exception e ) {
                LOG.warn( "audit frontmatter lookup({}) failed: {}", pageName, e.getMessage(), e );
                return Map.of();
            }
        };
    }

    /** Cluster-by-page-name lookup; null (read-auditing won't trigger by cluster) on any failure. */
    private static Function< String, String > clusterLookup( final StructuralIndexService structuralIndex ) {
        return pageName -> {
            try {
                if ( structuralIndex == null ) return null;
                return structuralIndex.resolveCanonicalIdFromSlug( pageName )
                    .flatMap( structuralIndex::getByCanonicalId )
                    .map( PageDescriptor::cluster )
                    .orElse( null );
            } catch ( final Exception e ) {
                LOG.warn( "audit cluster lookup({}) failed: {}", pageName, e.getMessage(), e );
                return null;
            }
        };
    }

    /** Attaches the audit listener to authn/authz/user and wires last-login stamping; returns that listener. */
    private static LastLoginEventListener attachAuthListeners( final AuditEventListener listener, final Managers m ) {
        if ( m.authn() != null ) m.authn().addWikiEventListener( listener );
        if ( m.authz() != null ) m.authz().addWikiEventListener( listener );
        if ( m.user() != null ) m.user().addWikiEventListener( listener );
        // LOGIN_AUTHENTICATED fires from the authentication manager; the user database is the write target.
        if ( m.authn() != null && m.user() != null && m.user().getUserDatabase() != null ) {
            final LastLoginEventListener lastLogin = new LastLoginEventListener( m.user().getUserDatabase() );
            m.authn().addWikiEventListener( lastLogin );
            return lastLogin;
        }
        return null;
    }

    /** Attaches the audit listener to the group manager, filter manager, page manager and page renamer. */
    private static void attachContentListeners( final AuditEventListener listener, final Managers m ) {
        if ( m.group() != null ) m.group().addWikiEventListener( listener );
        // Save events (POST_SAVE_END) are fired by the FilterManager at the end of doPostSaveFiltering.
        if ( m.filterManager() != null ) WikiEventManager.addWikiEventListener( m.filterManager(), listener );
        // PageManager has no addWikiEventListener on its interface; events are keyed on the instance.
        if ( m.pageManager() != null ) WikiEventManager.addWikiEventListener( m.pageManager(), listener );
        // DefaultPageRenamer fires WikiPageRenameEvent (with {"from":..,"to":..}) keyed on itself.
        if ( m.pageRenamer() != null ) WikiEventManager.addWikiEventListener( m.pageRenamer(), listener );
    }

    /** Parse the comma-separated {@code wikantik.audit.readClusters} value into a trimmed, non-empty set. */
    static Set< String > parseAuditedClusters( final String raw ) {
        if ( raw == null || raw.isBlank() ) return Set.of();
        final Set< String > out = new HashSet<>();
        for ( final String part : raw.split( "," ) ) {
            final String trimmed = part.trim();
            if ( !trimmed.isEmpty() ) out.add( trimmed );
        }
        return out;
    }
}
