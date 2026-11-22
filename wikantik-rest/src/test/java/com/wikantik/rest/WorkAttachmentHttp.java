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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wikantik.HttpMockFactory;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plumbing for the attachment routes, which need what {@link WorkHttp} does not capture: the bytes written through
 * {@code getOutputStream()}, the content type and length, repeated headers, and a multipart request. A separate
 * class so the shared one stays as it is.
 */
final class WorkAttachmentHttp {

    /** What a servlet wrote; a repeated header joins with a newline. */
    record Result( int status, String body, byte[] bytes, Map< String, String > headers, String contentType,
                   long contentLength ) {
        JsonElement json() {
            return JsonParser.parseString( body );
        }
    }

    private WorkAttachmentHttp() {
    }

    /** A request with the given content type and, when {@code part} is non-null, that part under the name file. */
    static Result call( final WorkResourceBase servlet, final String method, final String pathInfo,
                        final String requestContentType, final Part part, final Map< String, String > params,
                        final boolean partThrows ) throws Exception {
        return call( servlet, method, pathInfo, requestContentType, part, params, partThrows ? new IllegalStateException( "request too big" ) : null, null );
    }

    /** As above; {@code partFailure} (when non-null) is thrown by {@code getPart}, and the request is handed to {@code seen}. */
    static Result call( final WorkResourceBase servlet, final String method, final String pathInfo,
                        final String requestContentType, final Part part, final Map< String, String > params,
                        final Exception partFailure, final java.util.function.Consumer< HttpServletRequest > seen )
            throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/work/items" );
        if ( seen != null ) {
            seen.accept( req );
        }
        final boolean partThrows = partFailure != null;
        Mockito.doReturn( method ).when( req ).getMethod();
        Mockito.doReturn( "/api/work/items" ).when( req ).getServletPath();
        Mockito.doReturn( pathInfo ).when( req ).getPathInfo();
        Mockito.doReturn( requestContentType ).when( req ).getContentType();
        for ( final Map.Entry< String, String > e : params.entrySet() ) {
            Mockito.doReturn( e.getValue() ).when( req ).getParameter( e.getKey() );
        }
        if ( partThrows ) {
            Mockito.doThrow( partFailure ).when( req ).getPart( "file" );
        } else {
            Mockito.doReturn( part ).when( req ).getPart( "file" );
        }
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final AtomicInteger status = new AtomicInteger( 200 );
        final AtomicLong length = new AtomicLong( -1 );
        final AtomicReference< String > type = new AtomicReference<>();
        final Map< String, String > headers = new HashMap<>();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        Mockito.doReturn( new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener( final WriteListener l ) {
            }

            @Override
            public void write( final int b ) {
                bytes.write( b );
            }
        } ).when( resp ).getOutputStream();
        Mockito.doAnswer( inv -> {
            status.set( inv.getArgument( 0 ) );
            return null;
        } ).when( resp ).setStatus( Mockito.anyInt() );
        Mockito.doAnswer( inv -> {
            type.set( inv.getArgument( 0 ) );
            return null;
        } ).when( resp ).setContentType( Mockito.anyString() );
        Mockito.doAnswer( inv -> {
            length.set( inv.getArgument( 0 ) );
            return null;
        } ).when( resp ).setContentLengthLong( Mockito.anyLong() );
        Mockito.doAnswer( inv -> {
            headers.put( inv.getArgument( 0 ), inv.getArgument( 1 ) );
            return null;
        } ).when( resp ).setHeader( Mockito.anyString(), Mockito.anyString() );
        Mockito.doAnswer( inv -> {
            headers.merge( inv.getArgument( 0 ), inv.getArgument( 1 ), ( a, b ) -> a + "\n" + b );
            return null;
        } ).when( resp ).addHeader( Mockito.anyString(), Mockito.anyString() );
        servlet.service( req, resp );
        return new Result( status.get(), sw.toString(), bytes.toByteArray(), headers, type.get(), length.get() );
    }

    /** A part carrying {@code bytes} under {@code filename}; its size is reported as {@code reportedSize}. */
    static Part part( final String filename, final byte[] bytes, final long reportedSize ) throws Exception {
        final Part part = Mockito.mock( Part.class );
        Mockito.doReturn( filename ).when( part ).getSubmittedFileName();
        Mockito.doReturn( reportedSize ).when( part ).getSize();
        Mockito.doAnswer( inv -> new ByteArrayInputStream( bytes ) ).when( part ).getInputStream();
        return part;
    }
}
