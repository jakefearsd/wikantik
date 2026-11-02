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
package com.wikantik.extractcli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 *  Audits every page's access rules for #104: what the one reader ({@code AccessRules.read}) makes of each page, next to
 *  what the two old readers enforced (see {@link AclShapeAudit}).
 *
 *  <pre>
 *  java -cp wikantik-extract-cli.jar com.wikantik.extractcli.AclShapeAuditCli --dir docs/wikantik-pages
 *  WIKANTIK_AUDIT_USER=… WIKANTIK_AUDIT_PASSWORD=… \
 *  java -cp wikantik-extract-cli.jar com.wikantik.extractcli.AclShapeAuditCli --url https://wiki.wikantik.com [--format json]
 *  </pre>
 *
 *  <p>{@code --url} lists pages with {@code GET /api/pages} and reads each with {@code GET /api/pages/{name}}; an
 *  administrator gets directives unstripped and the server's own lock reasons ({@code accessRules}). That endpoint
 *  returns the frontmatter as parsed fields, so the audit rebuilds the text as {@code ---}, the fields dumped as YAML,
 *  {@code ---}, the body: shapes in the body are exact, a directive inside a frontmatter value is seen but its original
 *  quoting is not. A page the server locks that the rebuilt text does not is reported locked with the server's reasons.
 *  Credentials come from {@code WIKANTIK_AUDIT_TOKEN} (bearer API key) or {@code WIKANTIK_AUDIT_USER} +
 *  {@code WIKANTIK_AUDIT_PASSWORD} (basic) and are never printed. Requests are throttled to about 20 per second.</p>
 *
 *  <p>Exit codes: {@code 0} clean, {@code 1} at least one page is locked, {@code 2} at least one page could not be
 *  fetched (reported by name, never skipped), {@code 64} bad usage. The summary lists the locked, changed and ESCAPED
 *  pages; ESCAPED is the one shape whose new reading widens access against the old text scan.</p>
 */
public final class AclShapeAuditCli {

    private static final Logger LOG = LogManager.getLogger( AclShapeAuditCli.class );
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int PAGE_SIZE = 1_000;
    static final long DEFAULT_MIN_INTERVAL_MS = 50;

    private AclShapeAuditCli() {
    }

    /** What one page fetch returns: its text, and the server's own reasons when it locks the page (else empty). */
    record Fetched( String text, List< String > serverLockReasons ) {
    }

    /** Where pages come from. */
    interface PageSource {
        /** @return every page name; throws when the list cannot be read completely */
        List< String > names() throws IOException;

        /** @return one page's text */
        Fetched fetch( String name ) throws IOException;
    }

    /** One page that could not be fetched. */
    record FetchFailure( String name, String error ) {
    }

    // ---- run ----

    /** Audits every page of {@code source} and writes the report. @return the process exit code */
    static int run( final PageSource source, final boolean json, final PrintStream out, final PrintStream err ) {
        final List< String > names;
        try {
            names = source.names();
        } catch ( final IOException e ) {
            LOG.warn( "AclShapeAuditCli: cannot list pages: {}", e.getMessage() );
            err.println( "REFUSED: " + e.getMessage() );
            return 2;
        }
        final List< AclShapeAudit.PageAudit > audits = new ArrayList<>();
        final List< FetchFailure > failures = new ArrayList<>();
        for ( final String name : names ) {
            try {
                final Fetched f = source.fetch( name );
                audits.add( withServerReasons( AclShapeAudit.audit( name, f.text() ), f.serverLockReasons() ) );
            } catch ( final IOException | RuntimeException e ) {
                LOG.warn( "AclShapeAuditCli: cannot read page '{}': {}", name, e.getMessage() );
                failures.add( new FetchFailure( name, String.valueOf( e.getMessage() ) ) );
            }
        }
        if ( json ) {
            out.println( GSON.toJson( AclShapeAuditReport.jsonReport( audits, failures ) ) );
        } else {
            AclShapeAuditReport.printText( audits, failures, out );
        }
        if ( !failures.isEmpty() ) {
            return 2;
        }
        return audits.stream().anyMatch( AclShapeAudit.PageAudit::locked ) ? 1 : 0;
    }

    /** A page the server locks that the rebuilt text did not: reported locked, with the server's reasons. */
    private static AclShapeAudit.PageAudit withServerReasons( final AclShapeAudit.PageAudit a, final List< String > server ) {
        if ( server.isEmpty() || a.locked() ) {
            return a;
        }
        final List< String > reasons = new ArrayList<>( server );
        return new AclShapeAudit.PageAudit( a.name(), true, reasons, a.escaped(), a.oldTextScan(), a.oldRender(),
                AclShapeAudit.LOCKED, true );
    }


    // ---- main ----

    /** The Authorization header the environment asks for, or {@code null} when it names no credentials. */
    static String authorization( final UnaryOperator< String > env ) {
        final String token = env.apply( "WIKANTIK_AUDIT_TOKEN" );
        if ( token != null && !token.isBlank() ) {
            return "Bearer " + token.trim();
        }
        final String user = env.apply( "WIKANTIK_AUDIT_USER" );
        final String password = env.apply( "WIKANTIK_AUDIT_PASSWORD" );
        if ( user != null && !user.isBlank() && password != null ) {
            return "Basic " + Base64.getEncoder().encodeToString( ( user + ":" + password ).getBytes( StandardCharsets.UTF_8 ) );
        }
        return null;
    }

    /** @return the exit code; {@code 64} for bad usage */
    static int execute( final String[] args, final UnaryOperator< String > env, final long minIntervalMs,
                        final PrintStream out, final PrintStream err ) {
        String dir = null;
        String url = null;
        String format = "text";
        for ( int i = 0; i < args.length; i++ ) {
            switch ( args[ i ] ) {
                case "--dir" -> dir = i + 1 < args.length ? args[ ++i ] : null;
                case "--url" -> url = i + 1 < args.length ? args[ ++i ] : null;
                case "--format" -> format = i + 1 < args.length ? args[ ++i ] : null;
                default -> {
                    return usage( err );
                }
            }
        }
        if ( ( dir == null ) == ( url == null ) || !( "text".equals( format ) || "json".equals( format ) ) ) {
            return usage( err );
        }
        final PageSource source;
        if ( dir != null ) {
            source = AclShapeAuditSources.dirSource( Path.of( dir ) );
        } else {
            final String authorization = authorization( env );
            if ( authorization == null ) {
                err.println( "--url needs WIKANTIK_AUDIT_TOKEN, or WIKANTIK_AUDIT_USER and WIKANTIK_AUDIT_PASSWORD" );
                return 64;
            }
            source = AclShapeAuditSources.urlSource( url, authorization, minIntervalMs );
        }
        return run( source, "json".equals( format ), out, err );
    }

    private static int usage( final PrintStream err ) {
        err.println( "usage: AclShapeAuditCli (--dir <pages-dir> | --url <wiki-base-url>) [--format text|json]" );
        return 64;
    }

    public static void main( final String[] args ) {
        System.exit( execute( args, System::getenv, DEFAULT_MIN_INTERVAL_MS, System.out, System.err ) );
    }
}
