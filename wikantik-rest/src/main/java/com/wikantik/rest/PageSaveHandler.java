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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.SavedButPostSaveFailedException;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code PUT} (create or replace) and {@code PATCH} (metadata only) of {@code /api/pages/{name}}, with the save-side
 * validation limits and the 422/409/500 error ladder. The error-body shapes other save surfaces (blocks,
 * collab publish) reuse live in {@link PageSaveErrors}. Lives outside {@link PageResource} to keep that
 * servlet's complexity in check.
 */
final class PageSaveHandler {

    private static final Logger LOG = LogManager.getLogger( PageSaveHandler.class );
    private static final AtomicBoolean MAX_PAGE_BYTES_WARNED = new AtomicBoolean();

    private final RestServletBase servlet;

    PageSaveHandler( final RestServletBase servlet ) {
        this.servlet = servlet;
    }

    /**
     * D21: characters never permitted in a wiki page name. Colons, slashes, and
     * backslashes can resolve onto unintended filesystem paths or cluster ids; control
     * chars indicate header injection or copy-paste artefacts.
     */
    static boolean isInvalidPageName( final String name ) {
        if ( name == null || name.isEmpty() || name.length() > PageResource.MAX_PAGE_NAME_LENGTH ) {
            return true;
        }
        for ( int i = 0; i < name.length(); i++ ) {
            final char c = name.charAt( i );
            if ( c == ':' || c == '\\' || c == '/' || c < 0x20 || c == 0x7F ) {
                return true;
            }
        }
        return false;
    }

    /** {@code PUT}: creates or replaces the page; checks, in order, name, edit permission, body, size, version. */
    void handlePut( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        final String pageName = servlet.requirePathParam( request, response );
        if ( pageName == null ) return;
        // D5/D21: reject pathological page names BEFORE any provider call so the
        // response is a sanitized 400, not a 500 with a leaked filesystem path.
        if ( isInvalidPageName( pageName ) ) {
            servlet.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Page name is invalid: must be 1-" + PageResource.MAX_PAGE_NAME_LENGTH
                            + " characters and must not contain ':', '/', '\\', or control chars." );
            return;
        }
        if ( !servlet.checkPagePermission( request, response, pageName, "edit" ) ) return;

        LOG.debug( "PUT page: {}", pageName );

        final JsonObject body = servlet.parseJsonBody( request, response );
        if ( body == null ) return;

        final String content = acceptableContent( body, response );
        if ( content == null ) return;

        final int expectedVersion = servlet.getJsonInt( body, "expectedVersion", -1 );
        // D20: optional strict mode — refuse a PUT that doesn't carry expectedVersion.
        if ( expectedVersion < 0 && isExpectedVersionRequired( servlet.getEngine() ) ) {
            servlet.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "expectedVersion is required when " + PageResource.PROP_REQUIRE_EXPECTED_VERSION
                            + " is true. Pass the version returned by the prior GET to confirm "
                            + "you are not silently overwriting a concurrent update." );
            return;
        }

        final SaveOptions.Builder optionsBuilder = putOptions( body, expectedVersion );
        final String author = servlet.getJsonString( body, "author" );
        try {
            saveAndRespond( request, response, pageName, content, optionsBuilder, author );
        } catch ( final ContentValidationException e ) {
            // Math validation produced ERROR-severity violations — the page was not written.
            ContentWarningSink.clear();
            LOG.debug( "Math validation rejected save of {}: {}", pageName, e.getMessage() );
            response.setStatus( 422 );
            servlet.sendJson( response, PageSaveErrors.mathValidationErrorBody( e ) );
        } catch ( final AccessRuleShapeException e ) {
            // The save would add an access rule the wiki cannot read (it would lock the page) — not written.
            // The check runs after every filter, so both sinks hold this save's warnings: clear them, as the siblings do.
            FrontmatterWarningSink.clear();
            ContentWarningSink.clear();
            LOG.debug( "Access-rule shape check rejected save of {}: {}", pageName, e.getMessage() );
            response.setStatus( 422 );
            servlet.sendJson( response, PageSaveErrors.accessRuleShapeErrorBody( e ) );
        } catch ( final FrontmatterValidationException e ) {
            // Schema validation produced ERROR-severity violations — the page was not written.
            // Return the structured, field-addressable violations so the form can map them inline.
            FrontmatterWarningSink.clear();
            LOG.debug( "Frontmatter validation rejected save of {}: {}", pageName, e.getMessage() );
            response.setStatus( 422 );
            servlet.sendJson( response, PageSaveErrors.validationErrorBody( e ) );
        } catch ( final VersionConflictException e ) {
            LOG.debug( "Version conflict saving page {}: {}", pageName, e.getMessage() );
            servlet.sendError( response, HttpServletResponse.SC_CONFLICT, e.getMessage() );
        } catch ( final WikiException e ) {
            // D5: do not leak the underlying provider message verbatim — a deep IOException
            // may carry an absolute filesystem path. Log the real cause server-side; return
            // a sanitized message to the client.
            LOG.error( "Error saving page {}: {}", pageName, e.getMessage(), e );
            servlet.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    PageSaveErrors.sanitizeSaveErrorMessage( e ) );
        } catch ( final RuntimeException e ) {
            LOG.error( "Unexpected error saving page {}: {}", pageName, e.getMessage(), e );
            servlet.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error saving page: internal server error." );
        }
    }

    /**
     * The body's page text, or {@code null} after answering 400 (empty) or 413 (over the configured limit). An empty
     * text is refused up front: {@code PageManager.saveText("")} does not persist a page, so the caller would NPE on
     * {@code getVersion()}. The size limit (D22) is enforced before persistence.
     */
    private String acceptableContent( final JsonObject body, final HttpServletResponse response ) throws IOException {
        final String contentRaw = servlet.getJsonString( body, "content" );
        final String content = contentRaw != null ? contentRaw : "";
        if ( content.isEmpty() ) {
            servlet.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page content must not be empty" );
            return null;
        }
        final int maxBytes = parseMaxPageBytes( servlet.getEngine() );
        final int actualBytes = content.getBytes( java.nio.charset.StandardCharsets.UTF_8 ).length;
        if ( actualBytes > maxBytes ) {
            servlet.sendError( response, 413,
                    "Page body is " + actualBytes + " bytes; limit is " + maxBytes
                            + " bytes (configurable via " + PageResource.PROP_MAX_PAGE_BYTES + ")." );
            return null;
        }
        return content;
    }

    /** The save options a PUT body asks for, without the author (resolved per request). */
    private SaveOptions.Builder putOptions( final JsonObject body, final int expectedVersion ) {
        final SaveOptions.Builder optionsBuilder = SaveOptions.builder()
                .changeNote( servlet.getJsonString( body, "changeNote" ) )
                .expectedVersion( expectedVersion )
                .expectedContentHash( servlet.getJsonString( body, "expectedContentHash" ) );
        if ( body.has( "metadata" ) ) {
            @SuppressWarnings( "unchecked" )
            final Map< String, Object > metadata = RestServletBase.GSON.fromJson( body.get( "metadata" ), Map.class );
            if ( metadata != null ) {
                optionsBuilder.metadata( metadata );
                // The structured editor owns the full metadata object, so it sends replaceMetadata=true
                // to get replace (not merge) semantics — a field removed in the form is actually removed.
                if ( body.has( "replaceMetadata" ) && body.get( "replaceMetadata" ).getAsBoolean() ) {
                    optionsBuilder.replaceMetadata( true );
                }
            }
        }
        final String markupSyntax = servlet.getJsonString( body, "markupSyntax" );
        if ( markupSyntax != null ) {
            optionsBuilder.markupSyntax( markupSyntax );
        }
        return optionsBuilder;
    }

    /** Saves the page and answers the outcome; any failure propagates to {@link #handlePut}'s error ladder. */
    private void saveAndRespond( final HttpServletRequest request, final HttpServletResponse response,
                                 final String pageName, final String content, final SaveOptions.Builder optionsBuilder,
                                 final String bodyAuthor ) throws WikiException, IOException {
        final Engine engine = servlet.getEngine();

        // Resolve the author: when the session is authenticated, the session's LOGIN is authoritative
        // (ignore any caller-supplied body author to prevent spoofing). Fall back to the body author
        // only for the unauthenticated/headless case.
        final Session wikiSession = Wiki.session().find( engine, request );
        optionsBuilder.author( wikiSession.isAuthenticated() ? AuthorKeys.authorKeyFor( wikiSession ) : bodyAuthor );

        final PageSaveHelper helper = new PageSaveHelper( engine, servlet.getSubsystems().page().pages() );
        // Clear any stale warnings before the save; the SchemaValidationPageFilter stashes this
        // request's frontmatter warnings on the sink during saveText for us to return below.
        // Also clear the math-content warning sink.
        FrontmatterWarningSink.clear();
        ContentWarningSink.clear();
        final Saved outcome = saveReportingPostSaveFailure( helper, pageName, content, optionsBuilder.build() );
        final Page saved = outcome.page();

        // Defence in depth: a save that did not persist (e.g. vetoed by a filter)
        // returns null — surface a clean error rather than NPE on getVersion().
        if ( saved == null ) {
            LOG.warn( "Page save for '{}' did not persist a page (saveText returned null)", pageName );
            servlet.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Page save did not persist" );
            return;
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "success", true );
        result.put( "name", pageName );
        result.put( "version", Math.max( saved.getVersion(), 1 ) );
        result.put( "warnings", withPostSaveWarning( FrontmatterWarningSink.drain( pageName ), outcome ) );
        result.put( "mathWarnings", ContentWarningSink.drain() );

        servlet.sendJson( response, result );
    }

    /** A REST save's result: the page stored, and whether its post-save processing failed after it was stored. */
    private record Saved( Page page, boolean postSaveFailed ) { }

    /**
     * Saves, answering a save that was stored but whose post-save processing then failed as saved: the page WAS written,
     * so an error would invite a confused retry. The cause is logged here and never sent to the client (D5).
     */
    private static Saved saveReportingPostSaveFailure( final PageSaveHelper helper, final String pageName,
                                                       final String text, final SaveOptions options ) throws WikiException {
        try {
            return new Saved( helper.saveText( pageName, text, options ), false );
        } catch ( final SavedButPostSaveFailedException e ) {
            LOG.warn( "Page {} was saved as version {}, but its post-save processing failed; answering it as saved: {}",
                    pageName, e.outcome().version(), e.getCause(), e );
            return new Saved( e.outcome().page(), true );
        }
    }

    /** {@code warnings} plus the generic post-save warning when the save's post-save processing failed. */
    private static List< FieldViolation > withPostSaveWarning( final List< FieldViolation > warnings, final Saved outcome ) {
        if ( !outcome.postSaveFailed() ) {
            return warnings;
        }
        final List< FieldViolation > all = new ArrayList<>( warnings );
        all.add( FieldViolation.of( null, Severity.WARNING, PageResource.POST_SAVE_FAILED_CODE, PageResource.POST_SAVE_FAILED_MESSAGE ) );
        return all;
    }

    /** D22: read the configured max-bytes property, falling back to the default. */
    static int parseMaxPageBytes( final Engine engine ) {
        if ( engine == null ) {
            return PageResource.DEFAULT_MAX_PAGE_BYTES;
        }
        final String raw = com.wikantik.core.subsystem.CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties().getProperty( PageResource.PROP_MAX_PAGE_BYTES );
        if ( raw == null || raw.isBlank() ) {
            return PageResource.DEFAULT_MAX_PAGE_BYTES;
        }
        try {
            final int v = Integer.parseInt( raw.trim() );
            return v > 0 ? v : PageResource.DEFAULT_MAX_PAGE_BYTES;
        } catch ( final NumberFormatException nfe ) {
            // Operator typo must be visible, but this runs per request: WARN once, then DEBUG.
            if ( MAX_PAGE_BYTES_WARNED.compareAndSet( false, true ) ) {
                LOG.warn( "Property {} value '{}' is not an integer; using default {}: {}", PageResource.PROP_MAX_PAGE_BYTES, raw, PageResource.DEFAULT_MAX_PAGE_BYTES, nfe.getMessage() );
            } else {
                LOG.debug( "Property {} value '{}' is not an integer; using default {}", PageResource.PROP_MAX_PAGE_BYTES, raw, PageResource.DEFAULT_MAX_PAGE_BYTES );
            }
            return PageResource.DEFAULT_MAX_PAGE_BYTES;
        }
    }

    /** D20: read the strict-mode property; defaults to false. */
    static boolean isExpectedVersionRequired( final Engine engine ) {
        return engine != null && Boolean.parseBoolean(
                com.wikantik.core.subsystem.CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties().getProperty( PageResource.PROP_REQUIRE_EXPECTED_VERSION, "false" ).trim() );
    }

    /**
     * {@code PATCH}: metadata-only update. The body carries {@code metadata} (a map of frontmatter fields) and
     * {@code action}: "merge" (default) merges with the existing metadata, "replace" overwrites it.
     */
    @SuppressWarnings( "unchecked" )
    void handlePatch( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        final String pageName = servlet.requirePathParam( request, response );
        if ( pageName == null ) return;
        if ( !servlet.checkPagePermission( request, response, pageName, "edit" ) ) return;

        LOG.debug( "PATCH page: {}", pageName );

        final Engine engine = servlet.getEngine();
        final PageManager pm = servlet.getSubsystems().page().pages();
        final Page page = servlet.requirePage( request, response, pageName );
        if ( page == null ) return;

        final JsonObject body = servlet.parseJsonBody( request, response );
        if ( body == null ) return;

        if ( !body.has( "metadata" ) ) {
            servlet.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "metadata field is required" );
            return;
        }

        final Map< String, Object > callerMetadata = RestServletBase.GSON.fromJson( body.get( "metadata" ), Map.class );
        final String actionRaw = servlet.getJsonString( body, "action" );
        final String action = actionRaw != null ? actionRaw : "merge";

        // Read current page text and parse frontmatter
        final String currentText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
        final ParsedPage parsed = FrontmatterParser.parse( currentText );

        // Build new metadata
        final Map< String, Object > newMetadata;
        if ( "replace".equalsIgnoreCase( action ) ) {
            newMetadata = new LinkedHashMap<>( callerMetadata );
        } else {
            // Default: merge (caller wins)
            newMetadata = new LinkedHashMap<>( parsed.metadata() );
            newMetadata.putAll( callerMetadata );
        }

        // Reconstruct page text with updated frontmatter
        final String newText = FrontmatterWriter.write( newMetadata, parsed.body() );

        try {
            final PageSaveHelper helper = new PageSaveHelper( engine, pm );
            // D7: the PageSaveHelper builds a headless context so the author would
            // otherwise default to "Guest". Resolve it from the authenticated session's login
            // the same way doPut does.
            String effectiveAuthor = null;
            final Session wikiSession = Wiki.session().find( engine, request );
            if ( wikiSession.isAuthenticated() ) {
                effectiveAuthor = AuthorKeys.authorKeyFor( wikiSession );
            }
            final SaveOptions options = SaveOptions.builder()
                    .changeNote( "Metadata " + action + " via REST PATCH" )
                    .author( effectiveAuthor )
                    .build();
            final Saved outcome = saveReportingPostSaveFailure( helper, pageName, newText, options );
            final Page saved = outcome.page();

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "name", pageName );
            result.put( "version", Math.max( saved.getVersion(), 1 ) );
            if ( outcome.postSaveFailed() ) {
                result.put( "warnings", withPostSaveWarning( List.of(), outcome ) );
            }
            final PublicIdentity identity = servlet.getSubsystems().auth().publicIdentity();
            result.put( "author", IdentityDisplay.ofAuthor( identity, saved.getAuthor() ) ); // D7
            // Edit permission was enforced above, so the caller is an editor: stored frontmatter, exactly as written.
            result.put( "metadata", newMetadata );

            servlet.sendJson( response, result );

        } catch ( final AccessRuleShapeException e ) {
            LOG.debug( "Access-rule shape check rejected metadata patch of {}: {}", pageName, e.getMessage() );
            response.setStatus( 422 );
            servlet.sendJson( response, PageSaveErrors.accessRuleShapeErrorBody( e ) );
        } catch ( final WikiException e ) {
            LOG.error( "Error patching page {}: {}", pageName, e.getMessage() );
            servlet.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error patching page: " + e.getMessage() );
        }
    }
}
