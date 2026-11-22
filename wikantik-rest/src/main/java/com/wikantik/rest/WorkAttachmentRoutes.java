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
import com.wikantik.work.Attachment;
import com.wikantik.work.WorkAttachmentsService;
import com.wikantik.work.FileKinds;
import com.wikantik.work.WorkValidationException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * An item's attachment routes, registered into {@link WorkItemResource} (they live under
 * {@code /api/work/items/{key}/attachments}). Every route names the item and the attachment, and the service lets
 * an attachment be reached only through its own item. A download is never trusted to be safe to show: it is
 * {@code nosniff}, sandboxed, and shown inline only when it is one of the allow-listed raster images.
 */
final class WorkAttachmentRoutes {

    private static final Logger LOG = LogManager.getLogger( WorkAttachmentRoutes.class );
    private static final int MAX_COMMENT_DIGITS = 18;

    private final WorkResourceBase servlet;

    WorkAttachmentRoutes( final WorkResourceBase servlet ) {
        this.servlet = servlet;
    }

    void register( final SegmentRouteTable.Builder< WorkResourceBase.Handler > r ) {
        WorkResourceBase.route( r, "POST", "items/{}/attachments", this::upload );
        WorkResourceBase.route( r, "GET", "items/{}/attachments", c -> c.tree( WorkAttachmentJson.list(
            c.svc.features().attachments().listing( c.actor, c.pathParam( 0 ) ), c.pathParam( 0 ),
            v -> servlet.toTree( v, c.actor ) ) ) );
        WorkResourceBase.route( r, "GET", "items/{}/attachments/{}", this::download );
        WorkResourceBase.route( r, "DELETE", "items/{}/attachments/{}", c -> {
            c.svc.features().attachments().remove( c.actor, c.pathParam( 0 ), c.idParam( 1 ) );
            c.noContent();
        } );
    }

    // ---- upload

    private void upload( final WorkResourceBase.Call c ) throws Exception {
        final String key = c.pathParam( 0 );
        final long max = c.svc.features().attachments().maxFileBytes();
        // Before any of the body is touched: the refusal the upload itself would give (404 for a caller who cannot
        // see the item, 403 for one who may not attach, 409 for an archived project), so nothing is spooled for them.
        c.svc.features().attachments().mayAttach( c.actor, key );
        final String type = c.req.getContentType();
        if ( type == null || !type.toLowerCase( Locale.ROOT ).startsWith( "multipart/form-data" ) ) {
            servlet.sendError( c.resp, 415, "Uploading an attachment needs a multipart/form-data request." );
            return;
        }
        final Part part;
        try {
            part = c.req.getPart( "file" );
        } catch ( final IllegalStateException e ) {
            if ( isSizeLimit( e ) ) {
                LOG.warn( "Attachment upload to {} exceeds the container's multipart size limit: {}", key,
                    e.getMessage() );
                tooLarge( c.resp, max );
                return;
            }
            LOG.warn( "Attachment upload to {} was refused by the container (not a size limit): {}", key,
                e.getMessage(), e );
            throw new WorkValidationException( "file", "The upload could not be read as a single file." );
        } catch ( final ServletException e ) {
            LOG.warn( "Attachment upload to {} is not a readable multipart request: {}", key, e.getMessage() );
            throw new WorkValidationException( "file", "Send the file as multipart/form-data in a part named file." );
        }
        if ( part == null ) {
            throw new WorkValidationException( "file", "Attach a file in the multipart part named file." );
        }
        if ( part.getSize() > max ) {
            tooLarge( c.resp, max );
            return;
        }
        final Long commentId = commentId( c.query( "comment" ) );
        final byte[] bytes;
        try ( InputStream in = part.getInputStream() ) {
            bytes = in.readNBytes( (int) Math.min( max + 1, Integer.MAX_VALUE - 8 ) );
        }
        if ( bytes.length > max ) {
            tooLarge( c.resp, max );
            return;
        }
        final Attachment saved = c.svc.features().attachments().upload( c.actor, key, commentId,
            part.getSubmittedFileName(), bytes );
        WorkJson.write( c.resp, HttpServletResponse.SC_CREATED,
            WorkAttachmentJson.attachment( saved, key, v -> servlet.toTree( v, c.actor ) ) );
    }

    /**
     * Tomcat reports an over-size request as an {@code IllegalStateException} caused by the size exception of its
     * repackaged commons-fileupload ({@code SizeLimitExceededException}, {@code FileSizeLimitExceededException}, both
     * {@code SizeException}); a part-count overflow or a missing multipart-config is the same exception type without
     * such a cause. Matched by class name because Tomcat is not a compile dependency of this module.
     */
    private static boolean isSizeLimit( final Throwable e ) {
        int depth = 0;
        for ( Throwable t = e.getCause(); t != null && depth < 8; t = t.getCause(), depth++ ) {
            final String name = t.getClass().getSimpleName();
            if ( "SizeLimitExceededException".equals( name ) || "FileSizeLimitExceededException".equals( name )
                    || "SizeException".equals( name ) ) {
                return true;
            }
        }
        return false;
    }

    private static Long commentId( final String raw ) {
        if ( raw == null ) {
            return null;
        }
        if ( raw.length() > MAX_COMMENT_DIGITS || !raw.chars().allMatch( ch -> ch >= '0' && ch <= '9' ) ) {
            throw new WorkValidationException( "comment", "comment must be the id of a comment." );
        }
        return Long.valueOf( raw );
    }

    static void tooLarge( final HttpServletResponse resp, final long max ) throws IOException {
        final JsonObject body = WorkJson.errorBody( 413, "This file is larger than the limit of " + max + " bytes." );
        body.addProperty( "reason", "too_large" );
        WorkJson.write( resp, 413, body );
    }

    // ---- download

    private void download( final WorkResourceBase.Call c ) throws Exception {
        final long id = c.idParam( 1 );
        final HttpServletResponse resp = c.resp;
        // The service reads the bytes a piece at a time, so a large file is never held whole in heap; the headers go
        // out from begin(), before the first piece, and a refusal (not found) happens before begin().
        c.svc.features().attachments().download( c.actor, c.pathParam( 0 ), id, new WorkAttachmentsService.Download() {
            @Override
            public void begin( final Attachment a ) {
                final boolean inline = FileKinds.inline( a.contentType() );
                resp.setStatus( HttpServletResponse.SC_OK );
                resp.setContentType( inline ? a.contentType() : FileKinds.OCTET_STREAM );
                resp.setHeader( "Content-Disposition", WorkDownloadHeaders.disposition( inline, a.filename() ) );
                resp.setHeader( "X-Content-Type-Options", "nosniff" );
                resp.addHeader( "Content-Security-Policy", "sandbox; default-src 'none'" );
                resp.setHeader( "Cache-Control", inline ? "private, max-age=300" : "private, no-store" );
                resp.setContentLengthLong( a.sizeBytes() );
            }

            @Override
            public void chunk( final byte[] buffer, final int length ) throws IOException {
                resp.getOutputStream().write( buffer, 0, length );
            }
        } );
    }
}
