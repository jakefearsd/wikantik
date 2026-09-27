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

import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** {@link GoogleDriveApi} against a mocked {@link Drive} fluent client — no network. */
class GoogleDriveApiTest {

    @Test void listFolderPagesUntilNextPageTokenIsNull() throws IOException {
        final Drive drive = mock( Drive.class );
        final Drive.Files files = mock( Drive.Files.class );
        final Drive.Files.List list = mock( Drive.Files.List.class );
        when( drive.files() ).thenReturn( files );
        when( files.list() ).thenReturn( list );
        when( list.setQ( anyString() ) ).thenReturn( list );
        when( list.setFields( anyString() ) ).thenReturn( list );
        when( list.setPageSize( any() ) ).thenReturn( list );
        when( list.setPageToken( any() ) ).thenReturn( list );

        final File withModified = new File().setId( "f1" ).setName( "Doc1" ).setMimeType( "text/plain" )
            .setModifiedTime( new DateTime( 1700000000000L ) ).setWebViewLink( "https://example/f1" );
        final File withoutModified = new File().setId( "f2" ).setName( "Doc2" ).setMimeType( "text/plain" );

        final FileList page1 = new FileList().setFiles( List.of( withModified ) ).setNextPageToken( "tok2" );
        final FileList page2 = new FileList().setFiles( List.of( withoutModified ) ).setNextPageToken( null );
        when( list.execute() ).thenReturn( page1 ).thenReturn( page2 );

        final GoogleDriveApi api = new GoogleDriveApi( drive );
        final List< DriveFile > result = api.listFolder( "folder-1" );

        assertEquals( 2, result.size() );
        assertEquals( "f1", result.get( 0 ).id() );
        assertEquals( "Doc1", result.get( 0 ).name() );
        assertNotNull( result.get( 0 ).modifiedTime() );
        assertEquals( "f2", result.get( 1 ).id() );
        assertNull( result.get( 1 ).modifiedTime() );

        verify( list, times( 2 ) ).setQ( "'folder-1' in parents and trashed = false" );
        verify( list, times( 2 ) ).execute();
        // second page requested with the token carried from the first page's response
        verify( list ).setPageToken( "tok2" );
    }

    @Test void listFolderReturnsEmptyListWhenNoFilesOnSinglePage() throws IOException {
        final Drive drive = mock( Drive.class );
        final Drive.Files files = mock( Drive.Files.class );
        final Drive.Files.List list = mock( Drive.Files.List.class );
        when( drive.files() ).thenReturn( files );
        when( files.list() ).thenReturn( list );
        when( list.setQ( anyString() ) ).thenReturn( list );
        when( list.setFields( anyString() ) ).thenReturn( list );
        when( list.setPageSize( any() ) ).thenReturn( list );
        when( list.setPageToken( any() ) ).thenReturn( list );
        when( list.execute() ).thenReturn( new FileList().setFiles( List.of() ).setNextPageToken( null ) );

        final GoogleDriveApi api = new GoogleDriveApi( drive );
        assertTrue( api.listFolder( "empty" ).isEmpty() );
    }

    @Test void exportWritesExportedBytesIntoTheReturnedArray() throws IOException {
        final Drive drive = mock( Drive.class );
        final Drive.Files files = mock( Drive.Files.class );
        final Drive.Files.Export export = mock( Drive.Files.Export.class );
        when( drive.files() ).thenReturn( files );
        when( files.export( "file-1", "text/markdown" ) ).thenReturn( export );
        final byte[] payload = "exported content".getBytes();
        doAnswer( inv -> {
            final OutputStream out = inv.getArgument( 0 );
            out.write( payload );
            return null;
        } ).when( export ).executeMediaAndDownloadTo( any( OutputStream.class ) );

        final GoogleDriveApi api = new GoogleDriveApi( drive );
        assertArrayEquals( payload, api.export( "file-1", "text/markdown" ) );
    }

    @Test void getMediaWritesRawBytesIntoTheReturnedArray() throws IOException {
        final Drive drive = mock( Drive.class );
        final Drive.Files files = mock( Drive.Files.class );
        final Drive.Files.Get get = mock( Drive.Files.Get.class );
        when( drive.files() ).thenReturn( files );
        when( files.get( "file-2" ) ).thenReturn( get );
        final byte[] payload = "raw bytes".getBytes();
        doAnswer( inv -> {
            final OutputStream out = inv.getArgument( 0 );
            out.write( payload );
            return null;
        } ).when( get ).executeMediaAndDownloadTo( any( OutputStream.class ) );

        final GoogleDriveApi api = new GoogleDriveApi( drive );
        assertArrayEquals( payload, api.getMedia( "file-2" ) );
    }
}
