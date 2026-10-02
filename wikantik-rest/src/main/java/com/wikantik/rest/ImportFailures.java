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

import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wikantik.importer.ImportJobConflictException;
import com.wikantik.importer.ImportLimitException;
import com.wikantik.importer.VaultArchiveException;

/** The single failure-to-status mapper for {@link ObsidianImportResource}; every send goes through {@code sendError}. */
final class ImportFailures {

    private static final Logger LOG = LogManager.getLogger( ImportFailures.class );
    private static final int SC_TOO_MANY_REQUESTS = 429;

    private ImportFailures() {
    }

    static void send( final HttpServletResponse resp, final Exception e ) throws IOException {
        if ( e instanceof VaultArchiveException || e instanceof IllegalArgumentException ) {
            RestJson.sendError( resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage() );
        } else if ( e instanceof ImportLimitException ) {
            RestJson.sendError( resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, e.getMessage() );
        } else if ( e instanceof ImportJobConflictException c ) {
            RestJson.sendError( resp, c.reason() == ImportJobConflictException.Reason.USER_RUNNING
                ? HttpServletResponse.SC_CONFLICT : SC_TOO_MANY_REQUESTS, c.getMessage() );
        } else {
            LOG.warn( "Obsidian import failed: {}", e.getMessage(), e );
            // The detail (paths, SQL, provider internals) goes to the log only, never to the client.
            RestJson.sendError( resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Import failed; see server log" );
        }
    }
}
