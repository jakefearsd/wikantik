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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Batch CLI that walks a folder and POSTs each supported document to
 * {@code POST <base-url>/api/ingest} as a {@code multipart/form-data} request,
 * tallying created / updated / unchanged / failed results.
 *
 * <p>This is an <em>HTTP client only</em> — it does not touch the database
 * directly; a running Wikantik instance at {@code --base-url} is required.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * java -cp wikantik-extract-cli.jar com.wikantik.extractcli.IngestDocumentsCli \
 *      --base-url http://localhost:8080 \
 *      --dir /data/documents \
 *      --user admin \
 *      --password secret \
 *      --force
 * }</pre>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>{@code 0} — walk completed; all files processed without failure.</li>
 *   <li>{@code 1} — one or more files failed to ingest.</li>
 *   <li>{@code 2} — invalid CLI arguments.</li>
 * </ul>
 */
public final class IngestDocumentsCli {

    private static final Logger LOG = LogManager.getLogger( IngestDocumentsCli.class );

    /** Supported document extensions (lower-case, dot-prefixed). */
    static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".pdf", ".txt", ".md", ".docx", ".pptx", ".xlsx" );

    private IngestDocumentsCli() {}

    // ---- public seam types ----

    /** Posts a single file to the ingest endpoint; returns the {@code status} string from JSON. */
    @FunctionalInterface
    public interface FilePoster {
        String post( Path file ) throws Exception;
    }

    /** Immutable tally of ingest results. */
    public record Tally( int created, int updated, int unchanged, int failed ) {
        public int total() { return created + updated + unchanged + failed; }
    }

    // ---- entry point ----

    public static void main( final String[] args ) {
        final Args parsed;
        try {
            parsed = Args.parse( args );
        } catch ( final IllegalArgumentException e ) {
            System.err.println( "error: " + e.getMessage() );
            System.err.println();
            printUsage();
            System.exit( 2 );
            return;
        }
        if ( parsed.showHelp ) {
            printUsage();
            return;
        }
        final int exit = run( parsed );
        System.exit( exit );
    }

    // ---- core logic ----

    static int run( final Args a ) {
        final Path dir = Path.of( a.dir );
        if ( !Files.isDirectory( dir ) ) {
            System.err.println( "error: --dir is not a directory: " + dir );
            return 2;
        }

        final HttpClient http = HttpClient.newHttpClient();
        final FilePoster poster = buildHttpPoster( http, a );

        System.out.printf( "Ingest-CLI: walking %s → %s/api/ingest  force=%s%n",
            dir, a.baseUrl, a.force );

        final Tally tally;
        try {
            tally = runWalk( dir, poster );
        } catch ( final IOException e ) {
            LOG.warn( "Ingest-CLI: directory walk failed: {}", e.getMessage(), e );
            System.err.println( "error: directory walk failed — " + e.getMessage() );
            return 1;
        }

        System.out.printf(
            "%nIngest-CLI summary: total=%d  created=%d  updated=%d  unchanged=%d  failed=%d%n",
            tally.total(), tally.created(), tally.updated(), tally.unchanged(), tally.failed() );

        return tally.failed() > 0 ? 1 : 0;
    }

    /**
     * Walks {@code dir} recursively, filters to supported extensions, and invokes
     * {@code poster} per file. Returns the aggregated {@link Tally}.
     *
     * <p>This method is the primary unit-testable seam: inject a fake {@link FilePoster}
     * to exercise walk logic and tallying without a live server.</p>
     */
    static Tally runWalk( final Path dir, final FilePoster poster ) throws IOException {
        int created = 0, updated = 0, unchanged = 0, failed = 0;

        final List<Path> files = new ArrayList<>();
        try ( final var stream = Files.walk( dir ) ) {
            stream.filter( Files::isRegularFile )
                  .filter( p -> isSupportedExtension( p.getFileName().toString() ) )
                  .forEach( files::add );
        }

        for ( final Path file : files ) {
            final String name = file.getFileName().toString();
            try {
                final String status = poster.post( file );
                switch ( status.toLowerCase( Locale.ROOT ) ) {
                    case "created"   -> { created++;   System.out.printf( "  [created]   %s%n", name ); }
                    case "updated"   -> { updated++;   System.out.printf( "  [updated]   %s%n", name ); }
                    case "unchanged" -> { unchanged++; System.out.printf( "  [unchanged] %s%n", name ); }
                    default          -> { failed++;    System.out.printf( "  [unknown:%s] %s%n", status, name ); }
                }
            } catch ( final Exception e ) {
                failed++;
                LOG.warn( "Ingest-CLI: failed to ingest '{}': {}", name, e.getMessage(), e );
                System.out.printf( "  [FAILED]    %s — %s%n", name, e.getMessage() );
            }
        }

        return new Tally( created, updated, unchanged, failed );
    }

    // ---- extension filter ----

    /** Returns {@code true} when the filename ends with a supported extension (case-insensitive). */
    static boolean isSupportedExtension( final String filename ) {
        if ( filename == null ) { return false; }
        final int dot = filename.lastIndexOf( '.' );
        if ( dot < 0 ) { return false; }
        return SUPPORTED_EXTENSIONS.contains( filename.substring( dot ).toLowerCase( Locale.ROOT ) );
    }

    // ---- HTTP multipart poster ----

    private static FilePoster buildHttpPoster( final HttpClient http, final Args a ) {
        return file -> {
            final byte[] fileBytes = Files.readAllBytes( file );
            final String filename  = file.getFileName().toString();
            final String boundary  = "WikiantikIngestBoundary-" + Long.toHexString( System.nanoTime() );

            final byte[] body = buildMultipartBody( boundary, filename, fileBytes, a.force );

            final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri( URI.create( a.baseUrl + "/api/ingest" ) )
                .header( "Content-Type", "multipart/form-data; boundary=" + boundary )
                .header( "Authorization", basicAuthHeader( a.user, a.password ) )
                .POST( HttpRequest.BodyPublishers.ofByteArray( body ) );

            final HttpResponse<String> resp = http.send( reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString() );

            if ( resp.statusCode() < 200 || resp.statusCode() >= 300 ) {
                throw new IOException( "HTTP " + resp.statusCode() + " from /api/ingest: " + resp.body() );
            }

            return extractStatus( resp.body() );
        };
    }

    /**
     * Returns an {@code Authorization: Basic} header value for the given credentials.
     * The value is {@code "Basic " + base64(user + ":" + password)}, which is what
     * {@link com.wikantik.rest.BasicAuthFilter} expects.
     */
    static String basicAuthHeader( final String user, final String password ) {
        final String raw = user + ":" + password;
        final String encoded = Base64.getEncoder()
            .encodeToString( raw.getBytes( StandardCharsets.UTF_8 ) );
        return "Basic " + encoded;
    }

    /**
     * Builds a minimal {@code multipart/form-data} body manually.
     * Java's {@code java.net.http.HttpClient} has no built-in multipart support.
     */
    static byte[] buildMultipartBody( final String boundary,
                                      final String filename,
                                      final byte[] fileBytes,
                                      final boolean force ) {
        final var sb = new StringBuilder();
        final String CRLF = "\r\n";

        // file part
        sb.append( "--" ).append( boundary ).append( CRLF );
        sb.append( "Content-Disposition: form-data; name=\"file\"; filename=\"" )
          .append( filename ).append( '"' ).append( CRLF );
        sb.append( "Content-Type: application/octet-stream" ).append( CRLF );
        sb.append( CRLF );

        final byte[] header = sb.toString().getBytes( StandardCharsets.UTF_8 );

        final String afterFile = CRLF;

        // force field (optional)
        final byte[] forceField;
        if ( force ) {
            final String ff = "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"force\"" + CRLF
                + CRLF
                + "true" + CRLF;
            forceField = ff.getBytes( StandardCharsets.UTF_8 );
        } else {
            forceField = new byte[ 0 ];
        }

        final byte[] closing = ( "--" + boundary + "--" + CRLF ).getBytes( StandardCharsets.UTF_8 );
        final byte[] afterFileBytes = afterFile.getBytes( StandardCharsets.UTF_8 );

        final int totalLen = header.length + fileBytes.length + afterFileBytes.length
                           + forceField.length + closing.length;
        final byte[] result = new byte[ totalLen ];
        int pos = 0;
        System.arraycopy( header,         0, result, pos, header.length );         pos += header.length;
        System.arraycopy( fileBytes,       0, result, pos, fileBytes.length );     pos += fileBytes.length;
        System.arraycopy( afterFileBytes,  0, result, pos, afterFileBytes.length ); pos += afterFileBytes.length;
        System.arraycopy( forceField,      0, result, pos, forceField.length );    pos += forceField.length;
        System.arraycopy( closing,         0, result, pos, closing.length );
        return result;
    }

    /**
     * Extracts the {@code "status"} field from the JSON response
     * {@code {"page":"…","status":"created"}} without pulling in a JSON library.
     * Falls back to the raw body on parse failure.
     */
    static String extractStatus( final String json ) {
        // Minimal hand-parser: find "status":"<value>"
        final int idx = json.indexOf( "\"status\"" );
        if ( idx < 0 ) { return json.trim(); }
        final int colon = json.indexOf( ':', idx );
        if ( colon < 0 ) { return json.trim(); }
        final int q1 = json.indexOf( '"', colon + 1 );
        if ( q1 < 0 ) { return json.trim(); }
        final int q2 = json.indexOf( '"', q1 + 1 );
        if ( q2 < 0 ) { return json.trim(); }
        return json.substring( q1 + 1, q2 );
    }

    // ---- usage ----

    private static void printUsage() {
        System.out.println( """
            wikantik-ingest-documents — batch-ingest documents via POST /api/ingest.

            Usage:
              java -cp wikantik-extract-cli.jar com.wikantik.extractcli.IngestDocumentsCli [options]

            Required:
              --base-url <url>    Base URL of the running Wikantik instance (e.g. http://localhost:8080)
              --dir <path>        Directory to walk recursively for supported documents
              --user <login>      Admin login name (sent as HTTP Basic auth)
              --password <pass>   Admin password (sent as HTTP Basic auth)

            Optional:
              --force             Re-ingest even if the source SHA is unchanged
              -h, --help          Show this message

            Supported file types: .pdf  .txt  .md  .docx  .pptx  .xlsx  (case-insensitive)

            Exit codes: 0 = all succeeded, 1 = one or more failed, 2 = bad arguments.
            """ );
    }

    // ---- Args ----

    /** CLI argument bag parsed from {@code String[]}. */
    public static final class Args {
        public String  baseUrl  = null;
        public String  dir      = null;
        public String  user     = null;
        public String  password = null;
        public boolean force    = false;
        public boolean showHelp = false;

        public static Args parse( final String[] argv ) {
            final Args a = new Args();
            for ( int i = 0; i < argv.length; i++ ) {
                final String k = argv[ i ];
                switch ( k ) {
                    case "-h", "--help"  -> a.showHelp  = true;
                    case "--base-url"    -> a.baseUrl   = req( argv, ++i, k );
                    case "--dir"         -> a.dir       = req( argv, ++i, k );
                    case "--user"        -> a.user      = req( argv, ++i, k );
                    case "--password"    -> a.password  = req( argv, ++i, k );
                    case "--force"       -> a.force     = true;
                    default -> throw new IllegalArgumentException( "unknown argument: " + k );
                }
            }
            if ( !a.showHelp ) {
                if ( a.baseUrl == null || a.baseUrl.isBlank() ) {
                    throw new IllegalArgumentException( "--base-url is required" );
                }
                if ( a.dir == null || a.dir.isBlank() ) {
                    throw new IllegalArgumentException( "--dir is required" );
                }
                if ( a.user == null || a.user.isBlank() ) {
                    throw new IllegalArgumentException( "--user is required" );
                }
                if ( a.password == null || a.password.isBlank() ) {
                    throw new IllegalArgumentException( "--password is required" );
                }
            }
            return a;
        }

        private static String req( final String[] argv, final int i, final String flag ) {
            if ( i >= argv.length ) {
                throw new IllegalArgumentException( flag + " requires a value" );
            }
            return argv[ i ];
        }
    }
}
