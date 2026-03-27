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
 *   <li>{@code POST /api/attachments/PageName} - Upload an attachment</li>
 *   <li>{@code DELETE /api/attachments/PageName/filename.ext} - Delete an attachment</li>
 * </ul>
 */
public class AttachmentResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AttachmentResource.class );

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );
        if ( pathParam == null || pathParam.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name is required" );
            return;
        }

        // Determine if this is a list request or a download request
        final int slashIndex = pathParam.indexOf( '/' );
        final String parentPage = ( slashIndex < 0 ) ? pathParam : pathParam.substring( 0, slashIndex );
        if ( !checkPagePermission( request, response, parentPage, "view" ) ) return;

        if ( slashIndex < 0 ) {
            doListAttachments( pathParam, response );
        } else {
            final String pageName = pathParam.substring( 0, slashIndex );
            final String fileName = pathParam.substring( slashIndex + 1 );
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

        // Path should be just the page name for upload
        final String pageName = pathParam.contains( "/" ) ? pathParam.substring( 0, pathParam.indexOf( '/' ) ) : pathParam;
        if ( !checkPagePermission( request, response, pageName, "upload" ) ) return;

        LOG.debug( "POST attachment upload: {}", pageName );

        try {
            final Part filePart = request.getPart( "file" );
            if ( filePart == null ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File part 'file' is required" );
                return;
            }

            String fileName = filePart.getSubmittedFileName();
            if ( fileName == null || fileName.isBlank() ) {
                sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File name is required" );
                return;
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
    protected void doDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String pathParam = extractPathParam( request );
        if ( pathParam == null || pathParam.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Page name and file name are required" );
            return;
        }

        final int slashIndex = pathParam.indexOf( '/' );
        if ( slashIndex < 0 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File name is required in path: PageName/filename.ext" );
            return;
        }

        final String pageName = pathParam.substring( 0, slashIndex );
        final String fileName = pathParam.substring( slashIndex + 1 );
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
