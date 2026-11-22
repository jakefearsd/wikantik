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
import com.wikantik.work.AttachmentListing;
import com.wikantik.work.WorkAttachmentsService;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/** The attachment routes, reached through {@link WorkItemResource}. */
class WorkAttachmentRoutesTest {

    private static final String MULTIPART = "multipart/form-data; boundary=x";
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };
    private static final String BASE = "/PLAT-1/attachments";

    private WorkAttachmentsService attachments;
    private WorkItemResource servlet;

    @BeforeEach
    void setUp() throws Exception {
        final WorkService svc = Mockito.mock( WorkService.class );
        final WorkFeatures features = Mockito.mock( WorkFeatures.class );
        attachments = Mockito.mock( WorkAttachmentsService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.attachments() ).thenReturn( attachments );
        Mockito.when( attachments.maxFileBytes() ).thenReturn( 1000L );
        Mockito.when( attachments.listing( any(), any() ) ).thenReturn( listing( List.of(), true ) );
        servlet = WorkHttp.wire( new WorkItemResource(), svc, ALICE );
    }

    private static AttachmentListing listing( final List< Attachment > all, final boolean may ) {
        return new AttachmentListing( all, may, 1000L, 5000L, all.stream().mapToLong( Attachment::sizeBytes ).sum() );
    }

    private static Attachment attachment( final long id, final String name, final String type, final String by ) {
        return new Attachment( id, 7, null, name, type, 3, "00", by, null, Instant.parse( "2026-11-21T08:00:00Z" ),
            null );
    }

    private WorkAttachmentHttp.Result upload( final Part part, final Map< String, String > params ) throws Exception {
        return WorkAttachmentHttp.call( servlet, "POST", BASE, MULTIPART, part, params, false );
    }

    private WorkAttachmentHttp.Result get( final String path ) throws Exception {
        return WorkAttachmentHttp.call( servlet, "GET", path, null, null, Map.of(), false );
    }

    private void stored( final Attachment a, final byte[] bytes ) throws Exception {
        // Two pieces, as the service hands them over: the route must write each one and no more than its length.
        Mockito.doAnswer( inv -> {
            final WorkAttachmentsService.Download out = inv.getArgument( 3 );
            out.begin( new Attachment( a.id(), a.itemId(), a.commentId(), a.filename(), a.contentType(), bytes.length,
                a.sha256(), a.uploadedBy(), a.uploadedByAgentId(), a.uploadedAt(), a.removedAt() ) );
            final int half = bytes.length / 2;
            final byte[] first = new byte[ half + 2 ];
            System.arraycopy( bytes, 0, first, 0, half );
            out.chunk( first, half );
            final byte[] second = new byte[ bytes.length - half + 2 ];
            System.arraycopy( bytes, half, second, 0, bytes.length - half );
            out.chunk( second, bytes.length - half );
            return null;
        } ).when( attachments ).download( eq( ALICE ), eq( "PLAT-1" ), eq( a.id() ), any() );
    }

    @Test
    void uploadPassesTheBytesNameAndCommentAndAnswers201WithMarkdown() throws Exception {
        Mockito.when( attachments.upload( ALICE, "PLAT-1", 4L, "shot.png", PNG ) )
            .thenReturn( attachment( 9, "shot.png", "image/png", "alice" ) );
        final WorkAttachmentHttp.Result r = upload( WorkAttachmentHttp.part( "shot.png", PNG, PNG.length ),
            Map.of( "comment", "4" ) );
        assertEquals( 201, r.status() );
        final JsonObject o = r.json().getAsJsonObject();
        assertEquals( 9, o.get( "id" ).getAsInt() );
        assertEquals( "![shot.png](work-attachment:9)", o.get( "markdown" ).getAsString() );
        assertEquals( "/api/work/items/PLAT-1/attachments/9", o.get( "url" ).getAsString() );
        assertTrue( o.get( "inline" ).getAsBoolean() );
        assertFalse( o.has( "sha256" ) );
    }

    @Test
    void aFileThatIsNotAnImageGetsALinkNotAnImage() throws Exception {
        Mockito.when( attachments.upload( any(), any(), isNull(), any(), any() ) )
            .thenReturn( attachment( 10, "log.txt", "application/octet-stream", "alice" ) );
        final WorkAttachmentHttp.Result r = upload( WorkAttachmentHttp.part( "log.txt", new byte[] { 1 }, 1 ),
            Map.of() );
        assertEquals( "[log.txt](work-attachment:10)", r.json().getAsJsonObject().get( "markdown" ).getAsString() );
        assertFalse( r.json().getAsJsonObject().get( "inline" ).getAsBoolean() );
    }

    @Test
    void aNonMultipartUploadIs415() throws Exception {
        final WorkAttachmentHttp.Result r = WorkAttachmentHttp.call( servlet, "POST", BASE, "application/json", null,
            Map.of(), false );
        assertEquals( 415, r.status() );
        assertTrue( r.body().contains( "Uploading an attachment needs a multipart/form-data request." ) );
    }

    @Test
    void aStrangerGets404BeforeAnyBodyIsRead() throws Exception {
        Mockito.when( attachments.mayAttach( ALICE, "PLAT-1" ) ).thenThrow( new WorkNotFoundException( "no" ) );
        final Part part = WorkAttachmentHttp.part( "a.png", PNG, PNG.length );
        final WorkAttachmentHttp.Result r = upload( part, Map.of() );
        assertEquals( 404, r.status() );
        Mockito.verify( part, Mockito.never() ).getInputStream();
        Mockito.verify( attachments, Mockito.never() ).upload( any(), any(), any(), any(), any() );
        final WorkAttachmentHttp.Result overflow = WorkAttachmentHttp.call( servlet, "POST", BASE, MULTIPART, null,
            Map.of(), true );
        assertEquals( 404, overflow.status() );
    }

    @Test
    void aStrangerNeverMakesTheContainerReadTheBody() throws Exception {
        Mockito.when( attachments.mayAttach( ALICE, "PLAT-1" ) ).thenThrow( new WorkNotFoundException( "no" ) );
        final java.util.concurrent.atomic.AtomicReference< jakarta.servlet.http.HttpServletRequest > seen =
            new java.util.concurrent.atomic.AtomicReference<>();
        final WorkAttachmentHttp.Result r = WorkAttachmentHttp.call( servlet, "POST", BASE, MULTIPART,
            WorkAttachmentHttp.part( "a.png", PNG, PNG.length ), Map.of(), null, seen::set );
        assertEquals( 404, r.status() );
        Mockito.verify( seen.get(), Mockito.never() ).getPart( any() );
        Mockito.verify( seen.get(), Mockito.never() ).getParts();
    }

    @Test
    void aViewerGetsTheUploadsRefusalAndTheBodyIsNeverRead() throws Exception {
        Mockito.when( attachments.mayAttach( ALICE, "PLAT-1" ) )
            .thenThrow( new WorkDeniedException( "Viewers cannot attach files." ) );
        final java.util.concurrent.atomic.AtomicReference< jakarta.servlet.http.HttpServletRequest > seen =
            new java.util.concurrent.atomic.AtomicReference<>();
        final WorkAttachmentHttp.Result r = WorkAttachmentHttp.call( servlet, "POST", BASE, MULTIPART,
            WorkAttachmentHttp.part( "a.png", PNG, PNG.length ), Map.of(), null, seen::set );
        assertEquals( 403, r.status() );
        Mockito.verify( seen.get(), Mockito.never() ).getPart( any() );
        Mockito.verify( attachments, Mockito.never() ).upload( any(), any(), any(), any(), any() );
    }

    @Test
    void anArchivedProjectIsRefusedBeforeTheBodyToo() throws Exception {
        Mockito.when( attachments.mayAttach( ALICE, "PLAT-1" ) ).thenThrow(
            new WorkConflictException( "This project is archived.", WorkConflictException.ARCHIVED ) );
        final java.util.concurrent.atomic.AtomicReference< jakarta.servlet.http.HttpServletRequest > seen =
            new java.util.concurrent.atomic.AtomicReference<>();
        final WorkAttachmentHttp.Result r = WorkAttachmentHttp.call( servlet, "POST", BASE, MULTIPART,
            WorkAttachmentHttp.part( "a.png", PNG, PNG.length ), Map.of(), null, seen::set );
        assertEquals( 409, r.status() );
        Mockito.verify( seen.get(), Mockito.never() ).getPart( any() );
    }

    /** Stand-ins named as Tomcat's repackaged commons-fileupload names its size exceptions. */
    private static final class FileSizeLimitExceededException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static final class FileCountLimitExceededException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private WorkAttachmentHttp.Result failing( final Exception failure ) throws Exception {
        return WorkAttachmentHttp.call( servlet, "POST", BASE, MULTIPART, null, Map.of(), failure, null );
    }

    @Test
    void aSizeCauseIs413ButAnyOtherContainerRefusalIs400() throws Exception {
        final WorkAttachmentHttp.Result size = failing(
            new IllegalStateException( "too big", new FileSizeLimitExceededException() ) );
        assertEquals( 413, size.status() );
        assertEquals( "too_large", size.json().getAsJsonObject().get( "reason" ).getAsString() );
        for ( final Exception other : List.< Exception >of(
                new IllegalStateException( "Unable to process parts as no multi-part configuration has been provided" ),
                new IllegalStateException( "too many", new FileCountLimitExceededException() ) ) ) {
            final WorkAttachmentHttp.Result r = failing( other );
            assertEquals( 400, r.status(), other.getMessage() );
            assertFalse( r.body().contains( "larger than the limit" ) );
        }
    }

    @Test
    void aMalformedMultipartRequestIs400NamingFile() throws Exception {
        final WorkAttachmentHttp.Result r = failing( new jakarta.servlet.ServletException( "no boundary" ) );
        assertEquals( 400, r.status() );
        assertEquals( "file", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void aPartOverTheLimitIs413AndTheServiceIsNotCalled() throws Exception {
        final WorkAttachmentHttp.Result r = upload( WorkAttachmentHttp.part( "big.bin", new byte[ 1 ], 1001 ),
            Map.of() );
        assertEquals( 413, r.status() );
        assertEquals( 413, r.json().getAsJsonObject().get( "status" ).getAsInt() );
        assertEquals( "too_large", r.json().getAsJsonObject().get( "reason" ).getAsString() );
        assertTrue( r.body().contains( "larger than the limit of 1000 bytes" ) );
        Mockito.verify( attachments, Mockito.never() ).upload( any(), any(), any(), any(), any() );
    }

    @Test
    void aPartThatLiesAboutItsSizeIsStillStoppedAtTheLimit() throws Exception {
        final byte[] big = new byte[ 5000 ];
        final Part part = WorkAttachmentHttp.part( "big.bin", big, 10 );
        final WorkAttachmentHttp.Result r = upload( part, Map.of() );
        assertEquals( 413, r.status() );
        Mockito.verify( attachments, Mockito.never() ).upload( any(), any(), any(), any(), any() );
    }

    @Test
    void theContainersOwnLimitIs413() throws Exception {
        final WorkAttachmentHttp.Result r = failing(
            new IllegalStateException( "request too big", new FileSizeLimitExceededException() ) );
        assertEquals( 413, r.status() );
        assertEquals( "too_large", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void tooLargeFromTheServiceIs413WithItsReason() throws Exception {
        Mockito.when( attachments.upload( any(), any(), any(), any(), any() ) ).thenThrow(
            new WorkConflictException( "This item's files are over the limit.", WorkConflictException.TOO_LARGE ) );
        final WorkAttachmentHttp.Result r = upload( WorkAttachmentHttp.part( "a.png", PNG, PNG.length ), Map.of() );
        assertEquals( 413, r.status() );
        assertEquals( 413, r.json().getAsJsonObject().get( "status" ).getAsInt() );
        assertEquals( "too_large", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void aMissingPartIs400NamingFile() throws Exception {
        final WorkAttachmentHttp.Result r = upload( null, Map.of() );
        assertEquals( 400, r.status() );
        assertEquals( "file", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void aNonNumericCommentIs400NamingComment() throws Exception {
        final WorkAttachmentHttp.Result r = upload( WorkAttachmentHttp.part( "a.png", PNG, PNG.length ),
            Map.of( "comment", "4x" ) );
        assertEquals( 400, r.status() );
        assertEquals( "comment", r.json().getAsJsonObject().get( "field" ).getAsString() );
        Mockito.verify( attachments, Mockito.never() ).upload( any(), any(), any(), any(), any() );
    }

    @Test
    void aValidationRefusalFromTheServiceIs400() throws Exception {
        Mockito.when( attachments.upload( any(), any(), any(), any(), any() ) )
            .thenThrow( new WorkValidationException( "file", "The file is empty." ) );
        assertEquals( 400, upload( WorkAttachmentHttp.part( "a.png", new byte[ 1 ], 1 ), Map.of() ).status() );
    }

    @Test
    void listShowsDisplayNamesNeverLogins() throws Exception {
        Mockito.when( attachments.listing( ALICE, "PLAT-1" ) )
            .thenReturn( listing( List.of( attachment( 3, "a.png", "image/png", "bob" ) ), true ) );
        final WorkAttachmentHttp.Result r = get( BASE );
        assertEquals( 200, r.status() );
        assertTrue( r.body().contains( "Name of bob" ) );
        assertFalse( r.body().contains( "\"bob\"" ) );
        final JsonObject a = r.json().getAsJsonObject().getAsJsonArray( "attachments" ).get( 0 ).getAsJsonObject();
        assertFalse( a.get( "mine" ).getAsBoolean() );
        assertEquals( "human", a.get( "uploadedByKind" ).getAsString() );
    }

    @Test
    void theListCarriesTheLimitsTheUsedBytesAndMayAttachForAMember() throws Exception {
        Mockito.when( attachments.listing( ALICE, "PLAT-1" ) )
            .thenReturn( listing( List.of( attachment( 3, "a.png", "image/png", "bob" ) ), true ) );
        final JsonObject o = get( BASE ).json().getAsJsonObject();
        assertTrue( o.get( "mayAttach" ).getAsBoolean() );
        assertEquals( 1000L, o.get( "maxFileBytes" ).getAsLong() );
        assertEquals( 5000L, o.get( "maxItemBytes" ).getAsLong() );
        assertEquals( 3L, o.get( "usedBytes" ).getAsLong() );
        assertEquals( 1, o.getAsJsonArray( "attachments" ).size() );
    }

    @Test
    void aViewerGetsTheListWithMayAttachFalse() throws Exception {
        Mockito.when( attachments.listing( ALICE, "PLAT-1" ) )
            .thenReturn( listing( List.of( attachment( 3, "a.png", "image/png", "bob" ) ), false ) );
        final WorkAttachmentHttp.Result r = get( BASE );
        assertEquals( 200, r.status() );
        final JsonObject o = r.json().getAsJsonObject();
        assertFalse( o.get( "mayAttach" ).getAsBoolean() );
        assertEquals( 1, o.getAsJsonArray( "attachments" ).size() );
    }

    @Test
    void aStrangerGets404OnTheList() throws Exception {
        Mockito.when( attachments.listing( ALICE, "PLAT-1" ) ).thenThrow( new WorkNotFoundException( "no" ) );
        assertEquals( 404, get( BASE ).status() );
    }

    @Test
    void anItemFullRefusalFromTheServiceIs413WithItsOwnReason() throws Exception {
        Mockito.when( attachments.upload( any(), any(), any(), any(), any() ) ).thenThrow(
            new WorkConflictException( "This item's attachments would pass the limit.", WorkConflictException.ITEM_FULL ) );
        final WorkAttachmentHttp.Result r = upload( WorkAttachmentHttp.part( "a.png", PNG, PNG.length ), Map.of() );
        assertEquals( 413, r.status() );
        assertEquals( 413, r.json().getAsJsonObject().get( "status" ).getAsInt() );
        assertEquals( "item_full", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void anImageIsServedInlineWithItsTypeAndTheHeaders() throws Exception {
        stored( attachment( 5, "shot.png", "image/png", "bob" ), PNG );
        final WorkAttachmentHttp.Result r = get( BASE + "/5" );
        assertEquals( 200, r.status() );
        assertEquals( "image/png", r.contentType() );
        assertEquals( "inline; filename=\"shot.png\"; filename*=UTF-8''shot.png", r.headers().get( "Content-Disposition" ) );
        assertEquals( "nosniff", r.headers().get( "X-Content-Type-Options" ) );
        assertEquals( "sandbox; default-src 'none'", r.headers().get( "Content-Security-Policy" ) );
        assertEquals( "private, max-age=300", r.headers().get( "Cache-Control" ) );
        assertEquals( PNG.length, r.contentLength() );
        assertArrayEquals( PNG, r.bytes() );
    }

    @Test
    void htmlAndSvgAreDownloads() throws Exception {
        for ( final String[] c : new String[][] { { "x.html", "application/octet-stream" },
            { "x.svg", "application/octet-stream" }, { "y.html", "text/html" }, { "y.svg", "image/svg+xml" },
            { "z.pdf", "application/pdf" }, { "n.txt", "text/plain" }, { "x.xml", "application/xml" } } ) {
            stored( attachment( 6, c[ 0 ], c[ 1 ], "bob" ), "<script>alert(1)</script>".getBytes() );
            final WorkAttachmentHttp.Result r = get( BASE + "/6" );
            assertEquals( "application/octet-stream", r.contentType(), c[ 0 ] );
            assertTrue( r.headers().get( "Content-Disposition" ).startsWith( "attachment;" ), c[ 0 ] );
            assertEquals( "nosniff", r.headers().get( "X-Content-Type-Options" ), c[ 0 ] );
            assertEquals( "private, no-store", r.headers().get( "Cache-Control" ), c[ 0 ] );
        }
    }

    @Test
    void theDispositionCannotBeBrokenByTheName() throws Exception {
        stored( attachment( 5, "a\"bé.png", "image/png", "bob" ), PNG );
        final String h = get( BASE + "/5" ).headers().get( "Content-Disposition" );
        assertTrue( h.contains( "filename=\"a_b_.png\"" ), h );
        assertTrue( h.contains( "filename*=UTF-8''a%22b%C3%A9.png" ), h );
        assertFalse( h.contains( "\r" ) || h.contains( "\n" ) );
    }

    @Test
    void aHostileNameStaysInsideTheHeader() throws Exception {
        final String hostile = "x\r\nSet-Cookie: a=b; \"q\" 'r' (s) *t* 100%" + "z".repeat( 300 );
        final String h = WorkDownloadHeaders.disposition( false, hostile );
        assertFalse( h.contains( "\r" ) || h.contains( "\n" ), h );
        assertTrue( h.startsWith( "attachment; filename=\"x__Set-Cookie: a=b; _q_ 'r' (s) *t* 100%zzz" ), h );
        final String star = h.substring( h.indexOf( "filename*=UTF-8''" ) + 17 );
        assertTrue( star.matches( "[A-Za-z0-9!#$&+\\-.^_`|~%]+" ), star );
        assertFalse( star.contains( "'" ) || star.contains( "(" ) || star.contains( "*" ) );
        assertEquals( "download", WorkDownloadHeaders.asciiFallback( "" ) );
    }

    @Test
    void notFoundIsTheSameOnAllFourRoutes() throws Exception {
        final WorkNotFoundException nf = new WorkNotFoundException( "PLAT-1 #5 does not exist" );
        Mockito.when( attachments.upload( any(), any(), any(), any(), any() ) ).thenThrow( nf );
        Mockito.when( attachments.listing( any(), any() ) ).thenThrow( nf );
        Mockito.doThrow( nf ).when( attachments ).download( any(), any(), anyLong(), any() );
        Mockito.when( attachments.remove( any(), any(), anyLong() ) ).thenThrow( nf );
        final String[] bodies = {
            upload( WorkAttachmentHttp.part( "a.png", PNG, PNG.length ), Map.of() ).body(),
            get( BASE ).body(), get( BASE + "/5" ).body(),
            WorkAttachmentHttp.call( servlet, "DELETE", BASE + "/5", null, null, Map.of(), false ).body() };
        for ( final String b : bodies ) {
            assertEquals( bodies[ 0 ], b );
            assertTrue( b.contains( "Not found." ) );
            assertFalse( b.contains( "PLAT-1" ) );
        }
    }

    @Test
    void aNonNumericAttachmentIdIs404() throws Exception {
        assertEquals( 404, get( BASE + "/abc" ).status() );
        assertEquals( 404, WorkAttachmentHttp.call( servlet, "DELETE", BASE + "/abc", null, null, Map.of(), false )
            .status() );
        Mockito.verify( attachments, Mockito.never() ).content( any(), any(), anyLong() );
    }

    @Test
    void anAttachmentOfAnotherItemIsAskedForThroughThisItemsKeyOnly() throws Exception {
        get( "/OPS-2/attachments/5" );
        Mockito.verify( attachments ).download( eq( ALICE ), eq( "OPS-2" ), eq( 5L ), any() );
        Mockito.verify( attachments, Mockito.never() ).download( eq( ALICE ), eq( "PLAT-1" ), eq( 5L ), any() );
    }

    @Test
    void anArchivedRefusalIs409Archived() throws Exception {
        Mockito.when( attachments.upload( any(), any(), any(), any(), any() ) )
            .thenThrow( new WorkConflictException( "archived", WorkConflictException.ARCHIVED ) );
        final WorkAttachmentHttp.Result r = upload( WorkAttachmentHttp.part( "a.png", PNG, PNG.length ), Map.of() );
        assertEquals( 409, r.status() );
        assertEquals( "archived", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void aReportersShareRefusalIs413ReporterFullWithTheServicesWords() throws Exception {
        Mockito.when( attachments.upload( any(), any(), any(), any(), any() ) ).thenThrow(
            new WorkConflictException( "Your files on this request would pass the limit of 100 bytes.",
                WorkConflictException.REPORTER_FULL ) );
        final WorkAttachmentHttp.Result r = upload( WorkAttachmentHttp.part( "a.png", PNG, PNG.length ), Map.of() );
        assertEquals( 413, r.status() );
        assertEquals( "reporter_full", r.json().getAsJsonObject().get( "reason" ).getAsString() );
        assertTrue( r.body().contains( "would pass the limit of 100 bytes" ) );
    }

    @Test
    void theFilingCapIs429AndEveryOtherConflictKeepsItsStatus() {
        assertEquals( 429, WorkConflictStatus.of( WorkConflictException.INTAKE_LIMIT ) );
        assertEquals( 413, WorkConflictStatus.of( WorkConflictException.TOO_LARGE ) );
        assertEquals( 413, WorkConflictStatus.of( WorkConflictException.ITEM_FULL ) );
        assertEquals( 409, WorkConflictStatus.of( WorkConflictException.STALE ) );
        assertEquals( 409, WorkConflictStatus.of( WorkConflictException.ARCHIVED ) );
    }

    @Test
    void aDenialIs403() throws Exception {
        Mockito.when( attachments.upload( any(), any(), any(), any(), any() ) )
            .thenThrow( new WorkDeniedException( "Viewers cannot attach." ) );
        assertEquals( 403, upload( WorkAttachmentHttp.part( "a.png", PNG, PNG.length ), Map.of() ).status() );
        Mockito.when( attachments.remove( any(), any(), eq( 5L ) ) ).thenThrow( new WorkDeniedException( "no" ) );
        assertEquals( 403, WorkAttachmentHttp.call( servlet, "DELETE", BASE + "/5", null, null, Map.of(), false )
            .status() );
    }

    @Test
    void deleteIs204() throws Exception {
        final WorkAttachmentHttp.Result r = WorkAttachmentHttp.call( servlet, "DELETE", BASE + "/5", null, null,
            Map.of(), false );
        assertEquals( 204, r.status() );
        Mockito.verify( attachments ).remove( ALICE, "PLAT-1", 5L );
        assertNull( r.contentType() );
    }

    @Test
    void aReporterOnlyCallerSeesTheUploaderByDisplayNameNeverTheLogin() throws Exception {
        final com.wikantik.work.Actor rita = com.wikantik.work.Actor.human( "rita", java.util.Set.of(), false );
        final WorkService svc = Mockito.mock( WorkService.class );
        final WorkFeatures features = Mockito.mock( WorkFeatures.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.attachments() ).thenReturn( attachments );
        Mockito.when( attachments.listing( rita, "PLAT-4" ) ).thenReturn( listing( List.of(
            attachment( 3, "a.png", "image/png", "lena.login" ), attachment( 4, "b.txt", "text/plain", "rita" ) ), true ) );
        final WorkItemResource asRita = WorkHttp.wire( new WorkItemResource(), svc, rita );
        Mockito.doAnswer( inv -> "Display " + inv.getArgument( 0 ).toString().toUpperCase() ).when( asRita )
            .displayName( Mockito.anyString() );
        final WorkAttachmentHttp.Result r = WorkAttachmentHttp.call( asRita, "GET", "/PLAT-4/attachments",
            null, null, Map.of(), false );
        assertEquals( 200, r.status() );
        assertTrue( r.body().contains( "Display LENA.LOGIN" ) );
        assertFalse( r.body().contains( "\"lena.login\"" ), "the uploader's login is not on the wire" );
        assertFalse( r.body().contains( "\"rita\"" ), "nor the caller's own" );
        final var list = r.json().getAsJsonObject().getAsJsonArray( "attachments" );
        assertFalse( list.get( 0 ).getAsJsonObject().get( "mine" ).getAsBoolean() );
        assertTrue( list.get( 1 ).getAsJsonObject().get( "mine" ).getAsBoolean() );
    }
}
