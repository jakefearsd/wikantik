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
package com.wikantik.api.managers;

import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.WikiProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link AttachmentManager}'s default methods and the static
 * {@link AttachmentManager#validateFileName(String)} helper — a third-party
 * implementation that never overrides these must still get correct delegation.
 */
class AttachmentManagerTest {

    private AttachmentManager mockManager() {
        // CALLS_REAL_METHODS lets the interface's default methods actually execute
        // instead of being stubbed to return null.
        return Mockito.mock( AttachmentManager.class, Mockito.CALLS_REAL_METHODS );
    }

    // --- getAttachmentInfo(String) --------------------------------------------------

    @Test
    void getAttachmentInfoByNameReturnsNullForNullName() throws ProviderException {
        final AttachmentManager mgr = mockManager();
        assertNull( mgr.getAttachmentInfo( ( String ) null ) );
    }

    @Test
    void getAttachmentInfoByNameDelegatesWithLatestVersion() throws ProviderException {
        final AttachmentManager mgr = mockManager();
        final Attachment att = mock( Attachment.class );
        when( mgr.getAttachmentInfo( isNull( Context.class ), eq( "Foo/bar.txt" ), eq( WikiProvider.LATEST_VERSION ) ) )
                .thenReturn( att );

        final Attachment result = mgr.getAttachmentInfo( "Foo/bar.txt" );

        assertEquals( att, result );
        verify( mgr ).getAttachmentInfo( isNull( Context.class ), eq( "Foo/bar.txt" ), eq( WikiProvider.LATEST_VERSION ) );
    }

    // --- getAttachmentInfo(Context, String) -----------------------------------------

    @Test
    void getAttachmentInfoByContextDelegatesWithLatestVersion() throws ProviderException {
        final AttachmentManager mgr = mockManager();
        final Context ctx = mock( Context.class );
        final Attachment att = mock( Attachment.class );
        when( mgr.getAttachmentInfo( ctx, "Foo/bar.txt", WikiProvider.LATEST_VERSION ) ).thenReturn( att );

        final Attachment result = mgr.getAttachmentInfo( ctx, "Foo/bar.txt" );

        assertEquals( att, result );
        verify( mgr ).getAttachmentInfo( ctx, "Foo/bar.txt", WikiProvider.LATEST_VERSION );
    }

    // --- hasAttachments ---------------------------------------------------------------

    @Test
    void hasAttachmentsReturnsTrueWhenListNonEmpty() throws ProviderException {
        final AttachmentManager mgr = mockManager();
        final Page page = mock( Page.class );
        final Attachment att = mock( Attachment.class );
        when( mgr.listAttachments( page ) ).thenReturn( List.of( att ) );

        assertTrue( mgr.hasAttachments( page ) );
    }

    @Test
    void hasAttachmentsReturnsFalseWhenListEmpty() throws ProviderException {
        final AttachmentManager mgr = mockManager();
        final Page page = mock( Page.class );
        when( mgr.listAttachments( page ) ).thenReturn( List.of() );

        assertFalse( mgr.hasAttachments( page ) );
    }

    @Test
    void hasAttachmentsReturnsFalseAndSwallowsExceptionFromListAttachments() throws ProviderException {
        final AttachmentManager mgr = mockManager();
        final Page page = mock( Page.class );
        when( mgr.listAttachments( page ) ).thenThrow( new ProviderException( "backend down" ) );

        // Must not propagate — the default method logs and returns false.
        assertFalse( mgr.hasAttachments( page ) );
    }

    // --- getAttachmentStream(Attachment) ------------------------------------------------

    @Test
    void getAttachmentStreamByAttachmentDelegatesWithNullContext() throws IOException, ProviderException {
        final AttachmentManager mgr = mockManager();
        final Attachment att = mock( Attachment.class );
        final InputStream stream = new ByteArrayInputStream( "data".getBytes( StandardCharsets.UTF_8 ) );
        when( mgr.getAttachmentStream( isNull( Context.class ), eq( att ) ) ).thenReturn( stream );

        final InputStream result = mgr.getAttachmentStream( att );

        assertEquals( stream, result );
        verify( mgr ).getAttachmentStream( isNull( Context.class ), eq( att ) );
    }

    // --- storeAttachment(Attachment, File) -----------------------------------------------

    @Test
    void storeAttachmentFromFileReadsFileAndDelegatesToStreamOverload(
            @TempDir final Path tempDir ) throws IOException, ProviderException {
        final AttachmentManager mgr = mockManager();
        final Attachment att = mock( Attachment.class );
        final Path file = tempDir.resolve( "upload.txt" );
        Files.writeString( file, "hello attachment", StandardCharsets.UTF_8 );

        mgr.storeAttachment( att, file.toFile() );

        verify( mgr, times( 1 ) ).storeAttachment( eq( att ), any( InputStream.class ) );
    }

    @Test
    void storeAttachmentFromMissingFileThrowsIOException( @TempDir final Path tempDir ) {
        final AttachmentManager mgr = mockManager();
        final Attachment att = mock( Attachment.class );
        final Path missing = tempDir.resolve( "does-not-exist.txt" );

        assertThrows( IOException.class, () -> mgr.storeAttachment( att, missing.toFile() ) );
    }

    // --- validateFileName (static) --------------------------------------------------------

    @Test
    void validateFileNameThrowsForNullOrBlank() {
        assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( null ) );
        assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( "   " ) );
        assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( "" ) );
    }

    @Test
    void validateFileNameStripsLeadingPathComponents() throws WikiException {
        assertEquals( "bar.txt", AttachmentManager.validateFileName( "C:\\foo\\bar.txt" ) );
        assertEquals( "bar.txt", AttachmentManager.validateFileName( "/foo/bar.txt" ) );
    }

    @Test
    void validateFileNameTrimsTrailingDotsAndWhitespace() throws WikiException {
        assertEquals( "report", AttachmentManager.validateFileName( "  report...  " ) );
    }

    @Test
    void validateFileNameThrowsWhenNameEmptyAfterTrimmingDots() {
        assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( "..." ) );
    }

    @Test
    void validateFileNameThrowsForBlockedActiveContentExtensions() {
        for ( final String ext : List.of( "jsp", "jspf", "jspx", "jhtml", "phtml", "shtml",
                "html", "htm", "xhtml", "svg" ) ) {
            assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( "evil." + ext ),
                    "expected ." + ext + " to be blocked" );
        }
    }

    @Test
    void validateFileNameIsCaseInsensitiveForBlockedExtensions() {
        assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( "evil.JSP" ) );
    }

    @Test
    void validateFileNameAllowsOrdinaryExtensions() throws WikiException {
        assertEquals( "report.pdf", AttachmentManager.validateFileName( "report.pdf" ) );
        assertEquals( "image.png", AttachmentManager.validateFileName( "image.png" ) );
    }

    @Test
    void validateFileNameReplacesDangerousCharacters() throws WikiException {
        assertEquals( "a____b.txt", AttachmentManager.validateFileName( "a#?\"'b.txt" ) );
    }

    @Test
    void validateFileNameAllowsNameWithNoExtension() throws WikiException {
        assertEquals( "README", AttachmentManager.validateFileName( "README" ) );
    }
}
