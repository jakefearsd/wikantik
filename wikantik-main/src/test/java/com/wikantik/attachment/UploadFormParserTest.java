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
package com.wikantik.attachment;

import org.apache.commons.fileupload2.core.FileItem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pins the behavior of the multipart form-field dispatch that used to live as a switch inside
 * {@link AttachmentServlet#upload}.  These tests are the harness the refactor must stay
 * green against.
 */
class UploadFormParserTest {

    private static FileItem formField( final String name, final String value ) {
        try {
            final FileItem item = mock( FileItem.class );
            when( item.isFormField() ).thenReturn( true );
            when( item.getFieldName() ).thenReturn( name );
            when( item.getString( StandardCharsets.UTF_8 ) ).thenReturn( value );
            return item;
        } catch ( final IOException e ) {
            throw new AssertionError( "mock setup should not throw", e );
        }
    }

    private static FileItem file( final String filename ) {
        final FileItem item = mock( FileItem.class );
        when( item.isFormField() ).thenReturn( false );
        when( item.getName() ).thenReturn( filename );
        return item;
    }

    @Test
    void pageFieldIsTrimmedAtFirstSlash() throws IOException {
        final UploadFormData data = UploadFormParser.parse( List.of(
                formField( "page", "ParentPage/attachment.png" )
        ) );
        assertEquals( "ParentPage", data.wikipage() );
    }

    @Test
    void pageFieldWithoutSlashIsUsedWhole() throws IOException {
        final UploadFormData data = UploadFormParser.parse( List.of(
                formField( "page", "SomePage" )
        ) );
        assertEquals( "SomePage", data.wikipage() );
    }

    @Test
    void changeNoteHasHtmlEntitiesReplaced() throws IOException {
        final UploadFormData data = UploadFormParser.parse( List.of(
                formField( "changenote", "fixed <bug> & tested" )
        ) );
        // TextUtil.replaceEntities escapes &, <, >
        assertNotNull( data.changeNote() );
        assertFalse( data.changeNote().contains( "<bug>" ),
                "change note should have HTML entities replaced: " + data.changeNote() );
        assertTrue( data.changeNote().contains( "&lt;bug&gt;" ),
                "change note should contain escaped angle brackets: " + data.changeNote() );
    }

    @Test
    void nextPageIsCapturedRaw() throws IOException {
        // Validation of nextPage is a servlet concern; the parser just captures it
        final UploadFormData data = UploadFormParser.parse( List.of(
                formField( "nextpage", "http://external.example/evil" )
        ) );
        assertEquals( "http://external.example/evil", data.nextPage() );
    }

    @Test
    void fileItemsAreCollectedInOrder() throws IOException {
        final FileItem f1 = file( "a.png" );
        final FileItem f2 = file( "b.png" );
        final UploadFormData data = UploadFormParser.parse( List.of( f1, f2 ) );
        assertEquals( List.of( f1, f2 ), data.fileItems() );
    }

    @Test
    void unknownFormFieldsAreIgnored() throws IOException {
        final UploadFormData data = UploadFormParser.parse( List.of(
                formField( "surprise", "shouldnt-matter" ),
                formField( "page", "Home" )
        ) );
        assertEquals( "Home", data.wikipage() );
        assertNull( data.changeNote() );
        assertNull( data.nextPage() );
    }

    @Test
    void mixedFieldsAndFilesAreSeparated() throws IOException {
        final FileItem binary = file( "img.png" );
        final UploadFormData data = UploadFormParser.parse( List.of(
                formField( "page", "Docs" ),
                formField( "changenote", "uploaded" ),
                formField( "nextpage", "/Docs" ),
                binary
        ) );
        assertEquals( "Docs", data.wikipage() );
        assertEquals( "uploaded", data.changeNote() );
        assertEquals( "/Docs", data.nextPage() );
        assertEquals( List.of( binary ), data.fileItems() );
    }

    @Test
    void emptyItemListYieldsAllNullsAndNoFiles() throws IOException {
        final UploadFormData data = UploadFormParser.parse( List.of() );
        assertNull( data.wikipage() );
        assertNull( data.changeNote() );
        assertNull( data.nextPage() );
        assertTrue( data.fileItems().isEmpty() );
    }
}
