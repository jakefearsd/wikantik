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
package com.wikantik.work;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** A scripted outbound port: no network. {@code check} refuses private literals; {@code post} answers from a script (default 200). */
final class RecordingOutbound implements WorkOutbound {

    /** One post as the port received it. */
    record Sent( URI url, String contentType, String body, Map< String, String > headers ) {
    }

    /** One scripted answer: a status, or an exception to throw. */
    record Answer( int status, IOException failure ) {
        static Answer status( final int status ) {
            return new Answer( status, null );
        }

        static Answer fail( final IOException e ) {
            return new Answer( 0, e );
        }
    }

    final List< Sent > sent = new ArrayList<>();
    final Deque< Answer > script = new ArrayDeque<>();
    final List< URI > checked = new ArrayList<>();
    /** The status answered once the script is empty. */
    int defaultStatus = 200;
    /** When set, every post throws it (an unchecked failure from the port). */
    RuntimeException thrown;
    /** Runs at the start of every post (a test advances its clock here to make a send slow). */
    Runnable onPost = () -> { };

    @Override
    public void check( final URI url ) {
        checked.add( url );
        final String host = url.getHost();
        if ( host != null && ( host.startsWith( "10." ) || host.startsWith( "127." ) || "localhost".equals( host ) ) ) {
            throw new WorkValidationException( "url", "target host resolves to a non-routable/internal address: " + host );
        }
    }

    @Override
    public int post( final URI url, final String contentType, final String body, final Map< String, String > headers )
            throws IOException {
        sent.add( new Sent( url, contentType, body, headers ) );
        onPost.run();
        if ( thrown != null ) {
            throw thrown;
        }
        final Answer next = script.poll();
        if ( next == null ) {
            return defaultStatus;
        }
        if ( next.failure() != null ) {
            throw next.failure();
        }
        return next.status();
    }
}
