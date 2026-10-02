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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import com.wikantik.importer.ImportLimitException;
import com.wikantik.importer.ImportLimits;
import com.wikantik.importer.SpooledUpload;

/** Multipart envelope validation and spooling for {@link ObsidianImportResource}. */
final class ImportUploads {

    private final ImportLimits limits;
    private final Path spoolDir;

    ImportUploads( final ImportLimits limits ) {
        this( limits, SpooledUpload.defaultDir() );
    }

    ImportUploads( final ImportLimits limits, final Path spoolDir ) {
        this.limits = limits;
        this.spoolDir = spoolDir;
    }

    /** Validates the multipart envelope and returns the {@code file} part. */
    Part filePart( final HttpServletRequest req ) throws IOException, ServletException {
        final String contentType = req.getContentType();
        if ( contentType == null || !contentType.regionMatches( true, 0, "multipart/", 0, 10 ) ) {
            throw new UploadRejected( HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                "Vault import requires a multipart/form-data request" );
        }
        final Part part = req.getPart( "file" );
        if ( part == null ) {
            throw new UploadRejected( HttpServletResponse.SC_BAD_REQUEST, "File part 'file' is required" );
        }
        if ( part.getSize() > limits.maxUploadBytes() ) {
            throw new UploadRejected( HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "Upload exceeds " + ImportLimits.PROP_MAX_UPLOAD_BYTES + " (" + limits.maxUploadBytes() + ")" );
        }
        return part;
    }

    /** Copies the part to a temp file, capped at {@code wikantik.import.maxUploadBytes}. */
    SpooledUpload spool( final Part part ) throws IOException, ImportLimitException {
        return SpooledUpload.spool( spoolDir, part.getInputStream(),
            DerivedIngestResource.sanitizeFilename( part.getSubmittedFileName() ), limits.maxUploadBytes() );
    }

    /** A request rejected before any work was done, carrying the HTTP status to send. */
    static final class UploadRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        UploadRejected( final int status, final String message ) {
            super( message );
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
