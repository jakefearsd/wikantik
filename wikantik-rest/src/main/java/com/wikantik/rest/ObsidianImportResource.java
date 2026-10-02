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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.importer.ImportJobConflictException;
import com.wikantik.importer.ImportJobRegistry;
import com.wikantik.importer.ImportLimitException;
import com.wikantik.importer.ImportLimits;
import com.wikantik.importer.ImportOptions;
import com.wikantik.importer.PlanResult;
import com.wikantik.importer.SpooledUpload;
import com.wikantik.importer.VaultArchiveException;
import com.wikantik.importer.VaultImportJob;
import com.wikantik.importer.VaultImportService;

/**
 * {@code POST /api/import/obsidian/plan}, {@code POST /api/import/obsidian/apply},
 * {@code GET /api/import/obsidian/jobs/{id}} and {@code GET /api/import/obsidian/jobs/current} —
 * import a zipped Obsidian vault as wiki pages, hubs and attachments.
 *
 * <p>Every request needs an authenticated session holding {@code createPages}. {@code plan} is a
 * dry run; {@code apply} re-plans, verifies the caller's {@code planHash} still matches, and starts
 * a background job whose progress is read from the {@code jobs} endpoints. The in-memory
 * {@link ImportJobRegistry} is owned by this servlet and closed in {@link #destroy()}, which also
 * deletes any spooled uploads still held by jobs.
 */
public class ObsidianImportResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ObsidianImportResource.class );
    private static final Duration STALE_SPOOL_AGE = Duration.ofHours( 1 );
    private static final String JOBS_PREFIX = "/jobs/";

    private transient ImportJobRegistry registry;
    private transient ImportUploads uploads = new ImportUploads( ImportLimits.defaults() );

    /** An upload spooled to disk with its dry-run plan. */
    private record Planned( SpooledUpload upload, PlanResult plan ) {}

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        ImportLimits limits = ImportLimits.defaults();
        if ( getEngine() != null ) {
            limits = ImportLimits.fromProperties( getEngine().getWikiProperties() );
        }
        final Path dir = spoolDir();
        sweepStaleSpools( dir );
        uploads = new ImportUploads( limits, dir );
        registry = newRegistry( limits );
    }

    /** Test seam: the directory uploads are spooled to (and stale spools are swept from). */
    protected Path spoolDir() {
        return SpooledUpload.defaultDir();
    }

    private static void sweepStaleSpools( final Path dir ) {
        final int n = SpooledUpload.sweepStale( dir, STALE_SPOOL_AGE );
        if ( n > 0 ) {
            LOG.info( "Removed {} stale import spool file(s) from {}", n, dir );
        }
    }

    @Override
    public void destroy() {
        if ( registry != null ) {
            registry.close();
        }
        super.destroy();
    }

    /** Test seam: the job registry. */
    protected ImportJobRegistry newRegistry( final ImportLimits l ) {
        return ImportJobRegistry.create( l.maxConcurrent(), l.maxConcurrentPlans() );
    }

    /** Test seam: the import service. */
    protected VaultImportService importService() {
        return VaultImportService.fromSubsystems( getEngine(), getSubsystems() );
    }

    /** Enforces the {@code createPages} wiki permission (audited). Overridable for tests. */
    protected boolean canCreatePages( final Session session ) {
        return authz().checkPermission( session, WikiPermission.CREATE_PAGES );
    }

    /** Silent admin check used to let an admin read other users' jobs. Overridable for tests. */
    protected boolean isAdmin( final Session session ) {
        return authz().isPermitted( session, new AllPermission( getEngine().getApplicationName() ) );
    }

    ImportJobRegistry registry() {
        return registry;
    }

    private AuthorizationManager authz() {
        return AuthSubsystemBridge.fromLegacyEngine( getEngine() ).authorization();
    }

    private static String path( final HttpServletRequest req ) {
        return Optional.ofNullable( req.getPathInfo() ).orElse( "" );
    }

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final Session session = gate( req, resp );
        if ( session == null ) {
            return;
        }
        switch ( path( req ) ) {
            case "/plan" -> plan( req, resp );
            case "/apply" -> apply( req, resp, session );
            default -> sendNotFound( resp, "Unknown import path" );
        }
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final Session session = gate( req, resp );
        if ( session == null ) {
            return;
        }
        final String p = path( req );
        if ( "/jobs/current".equals( p ) ) {
            currentJob( resp, session );
        } else if ( p.startsWith( JOBS_PREFIX ) ) {
            job( resp, session, p.substring( JOBS_PREFIX.length() ) );
        } else {
            sendNotFound( resp, "Unknown import path" );
        }
    }

    /** Returns the caller's session, or sends 401/403 and returns {@code null}. */
    private Session gate( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final Session session = Wiki.session().find( getEngine(), req );
        if ( !session.isAuthenticated() ) {
            sendError( resp, HttpServletResponse.SC_UNAUTHORIZED, "Login required to import" );
            return null;
        }
        if ( !canCreatePages( session ) ) {
            sendError( resp, HttpServletResponse.SC_FORBIDDEN, "Forbidden: createPages permission required" );
            return null;
        }
        return session;
    }

    private void plan( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        SpooledUpload upload = null;
        try {
            final Part part = uploads.filePart( req );
            final ImportOptions options = ImportOptions.parse( req.getParameter( "clusterMode" ), req.getParameter( "cluster" ) );
            upload = uploads.spool( part );   // before the permit: a slow uploader must not hold a CPU permit
            try ( ImportJobRegistry.PlanPermit permit = registry.acquirePlan() ) {
                sendJson( resp, importService().plan( upload, options ).plan() );
            }
        } catch ( final ImportUploads.UploadRejected e ) {
            sendError( resp, e.status(), e.getMessage() );
        } catch ( final IOException | ServletException | ProviderException | VaultArchiveException
                       | ImportLimitException | ImportJobConflictException | RuntimeException e ) {
            ImportFailures.send( resp, e );
        } finally {
            if ( upload != null ) {
                upload.delete();
            }
        }
    }

    private void apply( final HttpServletRequest req, final HttpServletResponse resp, final Session session )
            throws IOException {
        final String owner = session.getLoginPrincipal().getName();
        final String author = session.getUserPrincipal().getName();
        Planned planned = null;
        boolean started = false;
        // The slot is held before re-planning (released on any failure by the try-with-resources), so concurrent
        // applies are refused up front instead of all re-planning; the plan permit is held only while planning.
        try ( ImportJobRegistry.Reservation slot = registry.reserve( owner ) ) {
            planned = spoolAndPlan( req );
            if ( !planned.plan().plan().planHash().equals( req.getParameter( "planHash" ) ) ) {
                sendError( resp, HttpServletResponse.SC_CONFLICT,
                    "The vault or the wiki changed since the plan was made; review the new plan" );
                return;
            }
            final Planned p = planned;
            final VaultImportService service = importService();
            final VaultImportJob job = slot.start(
                id -> service.newJob( id, owner, author, p.upload(), p.plan(), () -> canCreatePages( session ) ) );
            started = true;
            resp.setStatus( HttpServletResponse.SC_ACCEPTED );
            sendJson( resp, Map.of( "jobId", job.id() ) );
        } catch ( final ImportUploads.UploadRejected e ) {
            sendError( resp, e.status(), e.getMessage() );
        } catch ( final IOException | ServletException | ProviderException | VaultArchiveException
                       | ImportLimitException | ImportJobConflictException | RuntimeException e ) {
            ImportFailures.send( resp, e );
        } finally {
            if ( planned != null && !started ) {
                planned.upload().delete();
            }
        }
    }

    private Planned spoolAndPlan( final HttpServletRequest req ) throws IOException, ServletException, ProviderException,
            VaultArchiveException, ImportLimitException, ImportJobConflictException {
        final Part part = uploads.filePart( req );
        final ImportOptions options = ImportOptions.parse( req.getParameter( "clusterMode" ), req.getParameter( "cluster" ) );
        final SpooledUpload upload = uploads.spool( part );
        // The plan permit is taken only after the upload is spooled, so a slow uploader cannot starve permits.
        try ( ImportJobRegistry.PlanPermit permit = registry.acquirePlan() ) {
            return new Planned( upload, importService().plan( upload, options ) );
        } catch ( final IOException | ProviderException | VaultArchiveException | ImportLimitException
                       | ImportJobConflictException | RuntimeException e ) {
            upload.delete();
            throw e;
        }
    }

    private void job( final HttpServletResponse resp, final Session session, final String id ) throws IOException {
        final Optional< VaultImportJob > found = registry.find( id );
        if ( found.isEmpty() ) {
            sendNotFound( resp, "No such import job" );
        } else if ( !found.get().owner().equals( session.getLoginPrincipal().getName() ) && !isAdmin( session ) ) {
            sendError( resp, HttpServletResponse.SC_FORBIDDEN, "Forbidden: not your import job" );
        } else {
            sendJson( resp, found.get().view() );
        }
    }

    private void currentJob( final HttpServletResponse resp, final Session session ) throws IOException {
        final Optional< VaultImportJob > found = registry.current( session.getLoginPrincipal().getName() );
        if ( found.isPresent() ) {
            sendJson( resp, found.get().view() );
        } else {
            sendNotFound( resp, "No recent import job" );
        }
    }
}
