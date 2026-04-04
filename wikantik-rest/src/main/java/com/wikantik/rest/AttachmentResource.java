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

import com.wikantik.api.attachment.AttachmentNameValidator;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for attachment operations.
 * <p>
 * Mapped to {@code /api/attachments/*}. Handles:
 * <ul>
 *   <li>{@code GET /api/attachments/PageName} - List attachments for a page</li>
 *   <li>{@code GET /api/attachments/PageName/filename.ext} - Download an attachment</li>
 *   <li>{@code POST /api/attachments/PageName} - Upload an attachment (with optional {@code name} form field)</li>
 *   <li>{@code PUT /api/attachments/PageName/oldname.ext} - Rename an attachment</li>
 *   <li>{@code DELETE /api/attachments/PageName/filename.ext} - Delete an attachment</li>
 * </ul>
 * <p>
 * Path parsing handles hierarchical page names (e.g. {@code blog/admin/20260403Post}):
 * the last path segment containing a period is treated as a filename; everything before
 * it is the page name. If no segment contains a period, the entire path is the page name.
 */
public class AttachmentResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AttachmentResource.class );

    /**
     * Parses an attachment path into {@code [pageName, fileName]}.
     * Filenames always contain a period; page name segments don't.
     * If the last segment has a period, it's a filename; otherwise the entire path is the page name.
     *
     * @param path the raw path after {@code /api/attachments/}
     * @return {@code String[2]}: {@code [pageName, fileName]} where fileName may be {@code null}
     */
    static String[] parseAttachmentPath( final String path ) {
        final int lastSlash = path.lastIndexOf( '/' );
        if ( lastSlash < 0 ) {
            // Single segment — filename if it has a dot, otherwise page name
            return path.contains( "." )
                    ? new String[] { null, path }
                    : new String[] { path, null };
        }
        final String lastSegment = path.substring( lastSlash + 1 );
        if ( lastSegment.contains( "." ) ) {
            return new String[] { path.substring( 0, lastSlash ), lastSegment };
        }
        return new String[] { path, null };
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );
        if ( pathParam == null || pathParam.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }

        final String[] parsed = parseAttachmentPath( pathParam );
        final String pageName = parsed[0];
        final String fileName = parsed[1];

        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }

        if ( !checkPagePermission( request, response, pageName, "view" ) ) return;

        if ( fileName == null ) {
            doListAttachments( pageName, response );
        } else {
            doDownloadAttachment( pageName, fileName, response );
        }
    }

    /**
     * Lists all attachments for the given page.
     */
    private void doListAttachments( final String pageName, final HttpServletResponse response ) throws IOException {
        LOG.debug( "GET attachments list: {}", pageName );

        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );
        final Page page = pm.getPage( pageName );

        if ( page == null ) {
            sendNotFound( response, "Page not found: " + pageName );
            return;
        }

        try {
            final AttachmentManager am = engine.getManager( AttachmentManager.class );
            final List< Attachment > attachments = am.listAttachments( page );

            final List< Map< String, Object > > attList = attachments.stream()
                    .map( att -> {
                        final Map< String, Object > entry = new LinkedHashMap<>();
                        entry.put( "name", att.getName() );
                        entry.put( "fileName", att.getFileName() );
                        entry.put( "size", att.getSize() );
                        entry.put( "version", Math.max( att.getVersion(), 1 ) );
                        entry.put( "lastModified", att.getLastModified() );
                        return entry;
                    } )
                    .toList();

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "page", pageName );
            result.put( "attachments", attList );
            result.put( "count", attList.size() );

            sendJson( response, result );

        } catch ( final Exception e ) {
            LOG.error( "Error listing attachments for {}: {}", pageName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error listing attachments: " + e.getMessage() );
        }
    }

    /**
     * Downloads a specific attachment.
     */
    private void doDownloadAttachment( final String pageName, final String fileName,
                                        final HttpServletResponse response ) throws IOException {
        LOG.debug( "GET attachment download: {}/{}", pageName, fileName );

        final Engine engine = getEngine();
        final AttachmentManager am = engine.getManager( AttachmentManager.class );

        try {
            final Attachment att = am.getAttachmentInfo( pageName + "/" + fileName );
            if ( att == null ) {
                sendNotFound( response, "Attachment not found: " + pageName + "/" + fileName );
                return;
            }

            // Determine content type
            String contentType = URLConnection.getFileNameMap().getContentTypeFor( fileName );
            if ( contentType == null ) {
                contentType = "application/octet-stream";
            }

            setCorsHeaders( response );
            response.setContentType( contentType );
            response.setHeader( "Content-Disposition", "attachment; filename=\"" + fileName + "\"" );

            try ( final InputStream in = am.getAttachmentStream( att );
                  final OutputStream out = response.getOutputStream() ) {
                if ( in != null ) {
                    in.transferTo( out );
                }
            }

        } catch ( final Exception e ) {
            LOG.error( "Error downloading attachment {}/{}: {}", pageName, fileName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error downloading attachment: " + e.getMessage() );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );
        if ( pathParam == null || pathParam.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }

        // For upload, the entire path is the page name (no filename in URL)
        final String pageName = parseAttachmentPath( pathParam )[0];
        if ( pageName == null || pageName.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }
        if ( !checkPagePermission( request, response, pageName, "upload" ) ) return;

        LOG.debug( "POST attachment upload: {}", pageName );

        try {
            final Part filePart = request.getPart( "file" );
            if ( filePart == null ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File part 'file' is required" );
                return;
            }

            final String originalFileName = filePart.getSubmittedFileName();
            if ( originalFileName == null || originalFileName.isBlank() ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File name is required" );
                return;
            }

            // Use the 'name' form field if provided, otherwise fall back to original filename
            final String namePart = request.getParameter( "name" );
            final String fileName;
            if ( namePart != null && !namePart.isBlank() ) {
                if ( !AttachmentNameValidator.isValid( namePart ) ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            "Invalid attachment name. Use only a-zA-Z0-9._- (max 40 chars, exactly one period)." );
                    return;
                }
                if ( !AttachmentNameValidator.extensionsMatch( originalFileName, namePart ) ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            "Extension mismatch: uploaded file is ." + AttachmentNameValidator.getExtension( originalFileName )
                            + " but name has ." + AttachmentNameValidator.getExtension( namePart ) );
                    return;
                }
                fileName = namePart;
            } else {
                fileName = originalFileName;
            }

            final Engine engine = getEngine();
            final AttachmentManager am = engine.getManager( AttachmentManager.class );

            final Attachment att = Wiki.contents().attachment( engine, pageName, fileName );
            try ( final InputStream in = filePart.getInputStream() ) {
                am.storeAttachment( att, in );
            }

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "page", pageName );
            result.put( "fileName", fileName );
            result.put( "size", filePart.getSize() );

            sendJson( response, result );

        } catch ( final Exception e ) {
            LOG.error( "Error uploading attachment to {}: {}", pageName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error uploading attachment: " + e.getMessage() );
        }
    }

    @Override
    protected void doPut( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );
        if ( pathParam == null || pathParam.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name and current file name are required" );
            return;
        }

        final String[] parsed = parseAttachmentPath( pathParam );
        final String pageName = parsed[0];
        final String oldName = parsed[1];

        if ( pageName == null || oldName == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                    "Path must include page name and current filename: PageName/oldname.ext" );
            return;
        }

        if ( !checkPagePermission( request, response, pageName, "upload" ) ) return;

        LOG.debug( "PUT attachment rename: {}/{}", pageName, oldName );

        try {
            final JsonObject body = readJsonBody( request );
            final String newName = body.has( "newName" ) ? body.get( "newName" ).getAsString() : null;

            if ( newName == null || newName.isBlank() ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "newName is required" );
                return;
            }

            if ( !AttachmentNameValidator.isValid( newName ) ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid new name. Use only a-zA-Z0-9._- (max 40 chars, exactly one period)." );
                return;
            }

            if ( !AttachmentNameValidator.extensionsMatch( oldName, newName ) ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                        "Extension mismatch: cannot change ." + AttachmentNameValidator.getExtension( oldName )
                        + " to ." + AttachmentNameValidator.getExtension( newName ) );
                return;
            }

            final Engine engine = getEngine();
            final AttachmentManager am = engine.getManager( AttachmentManager.class );

            final Attachment oldAtt = am.getAttachmentInfo( pageName + "/" + oldName );
            if ( oldAtt == null ) {
                sendNotFound( response, "Attachment not found: " + pageName + "/" + oldName );
                return;
            }

            // Copy data to new name, then delete old
            final Attachment newAtt = Wiki.contents().attachment( engine, pageName, newName );
            try ( final InputStream in = am.getAttachmentStream( oldAtt ) ) {
                am.storeAttachment( newAtt, in );
            }
            am.deleteAttachment( oldAtt );

            // Fetch the stored attachment for accurate metadata
            final Attachment stored = am.getAttachmentInfo( pageName + "/" + newName );
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "page", pageName );
            result.put( "fileName", newName );
            result.put( "size", stored != null ? stored.getSize() : 0 );
            result.put( "version", stored != null ? Math.max( stored.getVersion(), 1 ) : 1 );

            sendJson( response, result );

        } catch ( final Exception e ) {
            LOG.error( "Error renaming attachment {}/{}: {}", pageName, oldName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error renaming attachment: " + e.getMessage() );
        }
    }

    @Override
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );
        if ( pathParam == null || pathParam.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name and file name are required" );
            return;
        }

        final String[] parsed = parseAttachmentPath( pathParam );
        final String pageName = parsed[0];
        final String fileName = parsed[1];

        if ( pageName == null || fileName == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File name is required in path: PageName/filename.ext" );
            return;
        }

        if ( !checkPagePermission( request, response, pageName, "delete" ) ) return;

        LOG.debug( "DELETE attachment: {}/{}", pageName, fileName );

        try {
            final Engine engine = getEngine();
            final AttachmentManager am = engine.getManager( AttachmentManager.class );

            final Attachment att = am.getAttachmentInfo( pageName + "/" + fileName );
            if ( att == null ) {
                sendNotFound( response, "Attachment not found: " + pageName + "/" + fileName );
                return;
            }

            am.deleteAttachment( att );

            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "success", true );
            result.put( "page", pageName );
            result.put( "fileName", fileName );

            sendJson( response, result );

        } catch ( final Exception e ) {
            LOG.error( "Error deleting attachment {}/{}: {}", pageName, fileName, e.getMessage() );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error deleting attachment: " + e.getMessage() );
        }
    }

}
