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

package com.wikantik.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal hand-rolled SMTP server for offline {@link MailUtil} coverage. Speaks just enough of
 * RFC 5321 (EHLO/HELO, MAIL, RCPT, DATA, RSET, NOOP, QUIT, plus a permissive AUTH LOGIN/PLAIN
 * exchange that never actually checks the credential) that a real {@code jakarta.mail} client can
 * complete a send against 127.0.0.1 on an ephemeral port. Exists purely to drive
 * {@link MailUtilSendTest} against the real wire protocol instead of mocking JavaMail's
 * internals — it is not a general-purpose SMTP implementation.
 */
final class FakeSmtpServer implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( FakeSmtpServer.class );

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private volatile boolean stopped;

    private volatile String rcptResponse = "250 2.1.5 OK";
    private volatile String mailFrom;
    private volatile String dataContents;
    private final List< String > rcptTo = new CopyOnWriteArrayList<>();

    FakeSmtpServer() throws IOException {
        serverSocket = new ServerSocket( 0, 50, InetAddress.getByName( "127.0.0.1" ) );
        acceptThread = new Thread( this::acceptLoop, "fake-smtp-accept" );
        acceptThread.setDaemon( true );
        acceptThread.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** When set, every RCPT TO gets this line instead of the default 250 (e.g. a 550 rejection). */
    void rejectRecipientsWith( final String smtpResponseLine ) {
        this.rcptResponse = smtpResponseLine;
    }

    String lastMailFrom() {
        return mailFrom;
    }

    List< String > lastRcptTo() {
        return rcptTo;
    }

    String lastData() {
        return dataContents;
    }

    @Override
    public void close() {
        stopped = true;
        try {
            serverSocket.close();
        } catch( final IOException e ) {
            LOG.warn( "failed to close fake SMTP server socket: {}", e.getMessage() );
        }
    }

    private void acceptLoop() {
        while( !stopped ) {
            try {
                final Socket socket = serverSocket.accept();
                final Thread sessionThread = new Thread( () -> handle( socket ), "fake-smtp-session" );
                sessionThread.setDaemon( true );
                sessionThread.start();
            } catch( final IOException e ) {
                if( !stopped ) {
                    LOG.warn( "fake SMTP accept loop error: {}", e.getMessage() );
                }
            }
        }
    }

    private void handle( final Socket socket ) {
        try( socket;
             final BufferedReader in = new BufferedReader( new InputStreamReader( socket.getInputStream(), StandardCharsets.UTF_8 ) );
             final OutputStream out = socket.getOutputStream() ) {
            write( out, "220 fake-smtp ESMTP ready" );
            String line;
            while( ( line = in.readLine() ) != null ) {
                final String upper = line.toUpperCase( Locale.ROOT );
                if( upper.startsWith( "EHLO" ) || upper.startsWith( "HELO" ) ) {
                    write( out, "250-fake-smtp Hello" );
                    write( out, "250-AUTH LOGIN PLAIN" );
                    write( out, "250 SIZE 10485760" );
                } else if( upper.startsWith( "MAIL FROM:" ) ) {
                    mailFrom = firstToken( line.substring( line.indexOf( ':' ) + 1 ) );
                    write( out, "250 2.1.0 OK" );
                } else if( upper.startsWith( "RCPT TO:" ) ) {
                    rcptTo.add( firstToken( line.substring( line.indexOf( ':' ) + 1 ) ) );
                    write( out, rcptResponse );
                } else if( upper.startsWith( "AUTH LOGIN" ) ) {
                    write( out, "334 VXNlcm5hbWU6" );
                    in.readLine(); // base64 username — not verified, this fake server accepts any credential
                    write( out, "334 UGFzc3dvcmQ6" );
                    in.readLine(); // base64 password — not verified
                    write( out, "235 2.7.0 Authentication successful" );
                } else if( upper.startsWith( "AUTH PLAIN" ) ) {
                    if( line.trim().equals( "AUTH PLAIN" ) ) {
                        write( out, "334 " );
                        in.readLine(); // base64 credential — not verified
                    }
                    write( out, "235 2.7.0 Authentication successful" );
                } else if( upper.startsWith( "DATA" ) ) {
                    write( out, "354 End data with <CR><LF>.<CR><LF>" );
                    dataContents = readData( in );
                    write( out, "250 2.0.0 OK: queued" );
                } else if( upper.startsWith( "RSET" ) ) {
                    write( out, "250 2.0.0 OK" );
                } else if( upper.startsWith( "NOOP" ) ) {
                    write( out, "250 2.0.0 OK" );
                } else if( upper.startsWith( "QUIT" ) ) {
                    write( out, "221 2.0.0 Bye" );
                    break;
                } else {
                    write( out, "500 5.5.2 Command not recognized" );
                }
            }
        } catch( final IOException e ) {
            LOG.warn( "fake SMTP session ended abnormally: {}", e.getMessage() );
        }
    }

    private static String readData( final BufferedReader in ) throws IOException {
        final StringBuilder data = new StringBuilder();
        String dataLine;
        while( ( dataLine = in.readLine() ) != null && !dataLine.equals( "." ) ) {
            data.append( dataLine ).append( '\n' );
        }
        return data.toString();
    }

    private static String firstToken( final String s ) {
        final String trimmed = s.trim();
        final int space = trimmed.indexOf( ' ' );
        return space < 0 ? trimmed : trimmed.substring( 0, space );
    }

    private static void write( final OutputStream out, final String line ) throws IOException {
        out.write( ( line + "\r\n" ).getBytes( StandardCharsets.UTF_8 ) );
        out.flush();
    }

}
