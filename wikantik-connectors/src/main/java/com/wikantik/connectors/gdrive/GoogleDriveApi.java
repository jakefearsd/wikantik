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
package com.wikantik.connectors.gdrive;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** {@link DriveApi} backed by a built {@link Drive} service. Package-private — only the factory builds it.
 *  Downloads are capped at {@value #MAX_DOWNLOAD_BYTES} bytes — an oversized file aborts the download
 *  with {@link IOException} (degraded by the connector to an incomplete batch) instead of buffering
 *  it whole (OOM defense). */
final class GoogleDriveApi implements DriveApi {

    /** 20 MiB — well above any text/markdown/exported-Doc content worth mirroring into a wiki page. */
    static final int MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024;

    private static final String FIELDS = "nextPageToken, files(id, name, mimeType, modifiedTime, webViewLink)";
    private final Drive drive;

    GoogleDriveApi( final Drive drive ) { this.drive = drive; }

    @Override
    public List< DriveFile > listFolder( final String folderId ) throws IOException {
        final List< DriveFile > out = new ArrayList<>();
        String pageToken = null;
        do {
            final FileList result = drive.files().list()
                .setQ( "'" + folderId + "' in parents and trashed = false" )
                .setFields( FIELDS )
                .setPageSize( 1000 )
                .setPageToken( pageToken )
                .execute();
            for ( final File f : result.getFiles() ) {
                out.add( new DriveFile( f.getId(), f.getName(), f.getMimeType(),
                    f.getModifiedTime() == null ? null : f.getModifiedTime().toStringRfc3339(),
                    f.getWebViewLink() ) );
            }
            pageToken = result.getNextPageToken();
        } while ( pageToken != null );
        return out;
    }

    @Override
    public byte[] export( final String fileId, final String mimeType ) throws IOException {
        final BoundedByteArrayOutputStream out = new BoundedByteArrayOutputStream( MAX_DOWNLOAD_BYTES );
        drive.files().export( fileId, mimeType ).executeMediaAndDownloadTo( out );
        return out.toByteArray();
    }

    @Override
    public byte[] getMedia( final String fileId ) throws IOException {
        final BoundedByteArrayOutputStream out = new BoundedByteArrayOutputStream( MAX_DOWNLOAD_BYTES );
        drive.files().get( fileId ).executeMediaAndDownloadTo( out );
        return out.toByteArray();
    }
}
