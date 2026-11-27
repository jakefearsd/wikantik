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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exit codes, argument handling and report text of {@link AclShapeAuditCli}, driven through in-memory sources. */
class AclShapeAuditCliContractTest {

    private static final String USAGE =
            "usage: AclShapeAuditCli (--dir <pages-dir> | --url <wiki-base-url>) [--format text|json]\n"
          + "  --dir on a byte-exact copy of the corpus is preferred: --url rebuilds the frontmatter from parsed\n"
          + "  fields, so its quoting and layout differ from the file. --url must be https (localhost excepted)\n"
          + "  and must not embed user:password@.\n";

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream( outBuf, true, StandardCharsets.UTF_8 );
    private final PrintStream err = new PrintStream( errBuf, true, StandardCharsets.UTF_8 );

    private String output() {
        return outBuf.toString( StandardCharsets.UTF_8 ).replace( "\r\n", "\n" );
    }

    private String errors() {
        return errBuf.toString( StandardCharsets.UTF_8 ).replace( "\r\n", "\n" );
    }

    private int exec( final String... args ) {
        return AclShapeAuditCli.execute( args, k -> null, 0, out, err );
    }

    /** A source backed by a map; a value of {@code null} makes the fetch fail with an IOException. */
    private static AclShapeAuditCli.PageSource source( final Map< String, AclShapeAuditCli.Fetched > pages ) {
        return new AclShapeAuditCli.PageSource() {
            @Override
            public List< String > names() {
                return List.copyOf( pages.keySet() );
            }

            @Override
            public AclShapeAuditCli.Fetched fetch( final String name ) throws IOException {
                final AclShapeAuditCli.Fetched f = pages.get( name );
                if ( f == null ) {
                    throw new IOException( "gone: " + name );
                }
                return f;
            }
        };
    }

    // ---- argument handling ----

    @Test
    void aFlagMissingItsValueIsAUsageErrorWithTheExactUsageText() {
        assertEquals( 64, exec( "--dir" ) );
        assertEquals( USAGE, errors() );
    }

    @Test
    void aDanglingFormatFlagIsAUsageError() {
        assertEquals( 64, exec( "--dir", "x", "--format" ) );
        assertEquals( USAGE, errors() );
    }

    @Test
    void aDanglingUrlFlagIsAUsageError() {
        assertEquals( 64, exec( "--url" ) );
        assertEquals( USAGE, errors() );
    }

    @Test
    void anUnknownFlagIsAUsageErrorAndWritesNothingToStdout() {
        assertEquals( 64, exec( "--bogus" ) );
        assertEquals( USAGE, errors() );
        assertEquals( "", output() );
    }

    @Test
    void theValueAfterAFlagIsConsumedNotTreatedAsAFlag() {
        // "--format" then "--dir": the value of --format is "--dir", which is not text|json.
        assertEquals( 64, exec( "--format", "--dir", "x" ) );
    }

    // ---- url mode preconditions ----

    @Test
    void urlModeWithoutCredentialsSaysSoAndExits64() {
        assertEquals( 64, exec( "--url", "https://wiki.example.com" ) );
        assertEquals( "--url needs WIKANTIK_AUDIT_TOKEN, or WIKANTIK_AUDIT_USER and WIKANTIK_AUDIT_PASSWORD\n", errors() );
    }

    @Test
    void urlModeWithAnUnsafeUrlPrintsTheReasonAndExits64() {
        final int code = AclShapeAuditCli.execute( new String[]{ "--url", "http://wiki.example.com" },
                Map.of( "WIKANTIK_AUDIT_TOKEN", "t" )::get, 0, out, err );
        assertEquals( 64, code );
        assertEquals( "--url must be https when credentials are sent (plain http only for localhost/127.0.0.1)\n", errors() );
    }

    @Test
    void unsafeUrlReasonTable() {
        assertEquals( "--url is not a valid URL", AclShapeAuditCli.unsafeUrlReason( "http://exa mple.com" ) );
        assertEquals( "--url must not embed credentials (user:password@); use WIKANTIK_AUDIT_TOKEN or WIKANTIK_AUDIT_USER/PASSWORD",
                      AclShapeAuditCli.unsafeUrlReason( "https://u:p@wiki.example.com" ) );
        assertEquals( "--url must be an absolute http(s) URL with a host", AclShapeAuditCli.unsafeUrlReason( "wiki.example.com" ) );
        assertEquals( "--url must be an absolute http(s) URL with a host", AclShapeAuditCli.unsafeUrlReason( "https:///path" ) );
        final String plain = "--url must be https when credentials are sent (plain http only for localhost/127.0.0.1)";
        assertEquals( plain, AclShapeAuditCli.unsafeUrlReason( "http://wiki.example.com" ) );
        assertEquals( plain, AclShapeAuditCli.unsafeUrlReason( "ftp://127.0.0.2" ) );
        assertNull( AclShapeAuditCli.unsafeUrlReason( "https://wiki.example.com" ) );
        assertNull( AclShapeAuditCli.unsafeUrlReason( "HTTPS://wiki.example.com" ) );
        assertNull( AclShapeAuditCli.unsafeUrlReason( "http://localhost:8080" ) );
        assertNull( AclShapeAuditCli.unsafeUrlReason( "http://LOCALHOST" ) );
        assertNull( AclShapeAuditCli.unsafeUrlReason( "http://127.0.0.1:8080" ) );
        assertNull( AclShapeAuditCli.unsafeUrlReason( "http://[::1]:8080" ) );
    }

    // ---- run(): server lock reasons, exit codes, report text ----

    @Test
    void aPageTheServerLocksButTheTextDoesNotIsReportedLockedWithTheServersReasons() {
        final Map< String, AclShapeAuditCli.Fetched > pages = new LinkedHashMap<>();
        pages.put( "Quiet", new AclShapeAuditCli.Fetched( "plain text\n", List.of( "server: IN_FRONTMATTER field summary: x" ) ) );
        final int code = AclShapeAuditCli.run( source( pages ), false, out, err );
        assertEquals( 1, code );
        final String o = output();
        assertTrue( o.contains( "Quiet\n  Reading: Locked\n    reason: server: IN_FRONTMATTER field summary: x\n" ), o );
        assertTrue( o.contains( "  new ACL:           LOCKED\n" ), o );
        assertTrue( o.contains( "locked: 1\n  Quiet\n" ), o );
    }

    @Test
    void serverReasonsAreIgnoredWhenTheTextIsAlreadyLocked() {
        final Map< String, AclShapeAuditCli.Fetched > pages = new LinkedHashMap<>();
        pages.put( "Denied", new AclShapeAuditCli.Fetched( "[{DENY view Bob}]\n", List.of( "server: ignored" ) ) );
        assertEquals( 1, AclShapeAuditCli.run( source( pages ), false, out, err ) );
        assertTrue( !output().contains( "server: ignored" ), output() );
    }

    @Test
    void aPageWithNoServerReasonsKeepsItsOwnReading() {
        final Map< String, AclShapeAuditCli.Fetched > pages = new LinkedHashMap<>();
        pages.put( "Plain", new AclShapeAuditCli.Fetched( "text\n", List.of() ) );
        assertEquals( 0, AclShapeAuditCli.run( source( pages ), false, out, err ) );
        assertTrue( output().contains( "Plain\n  Reading: " ), output() );
    }

    @Test
    void aFetchFailureWinsOverALockedPageAndIsListedByName() {
        final Map< String, AclShapeAuditCli.Fetched > pages = new LinkedHashMap<>();
        pages.put( "Denied", new AclShapeAuditCli.Fetched( "[{DENY view Bob}]\n", List.of() ) );
        pages.put( "Gone", null );
        assertEquals( 2, AclShapeAuditCli.run( source( pages ), false, out, err ) );
        final String o = output();
        assertTrue( o.contains( "Gone\n  FETCH FAILED: gone: Gone\n" ), o );
        assertTrue( o.contains( "pages: 2  audited: 1  fetch failures: 1\n" ), o );
        assertTrue( o.contains( "fetch failures: 1\n  Gone\n" ), o );
    }

    @Test
    void textReportHasTheExactSummaryBlockForACleanRun() {
        final Map< String, AclShapeAuditCli.Fetched > pages = new LinkedHashMap<>();
        pages.put( "Plain", new AclShapeAuditCli.Fetched( "text\n", List.of() ) );
        assertEquals( 0, AclShapeAuditCli.run( source( pages ), false, out, err ) );
        final String o = output();
        assertEquals( "\n== Summary ==\npages: 1  audited: 1  fetch failures: 0\nlocked: 0\nchanged: 0\nESCAPED: 0\nfetch failures: 0\n",
                      o.substring( o.indexOf( "\n== Summary ==" ) ) );
    }

    @Test
    void eachPageBlockPrintsTheOldAndNewAclLinesAndTheChangedFlag() {
        final Map< String, AclShapeAuditCli.Fetched > pages = new LinkedHashMap<>();
        pages.put( "Esc", new AclShapeAuditCli.Fetched( "\\[{ALLOW view Admin}]\n", List.of() ) );
        pages.put( "Same", new AclShapeAuditCli.Fetched( "[{ALLOW view Admin}]\n", List.of() ) );
        AclShapeAuditCli.run( source( pages ), false, out, err );
        final String o = output();
        assertTrue( o.contains( "  old text-scan ACL: " ), o );
        assertTrue( o.contains( "  old render ACL:    " ), o );
        assertTrue( o.contains( "  new ACL:           " ), o );
        assertTrue( o.contains( "  changed: yes  ESCAPED\n" ), o );
        assertTrue( o.contains( "  changed: no\n" ), o );
    }

    @Test
    void jsonSummaryCountsFailuresInPagesButNotInAudited() {
        final Map< String, AclShapeAuditCli.Fetched > pages = new LinkedHashMap<>();
        pages.put( "Plain", new AclShapeAuditCli.Fetched( "text\n", List.of() ) );
        pages.put( "Gone", null );
        assertEquals( 2, AclShapeAuditCli.run( source( pages ), true, out, err ) );
        final var summary = com.google.gson.JsonParser.parseString( output() ).getAsJsonObject().getAsJsonObject( "summary" );
        assertEquals( 2, summary.get( "pages" ).getAsInt() );
        assertEquals( 1, summary.get( "audited" ).getAsInt() );
        assertEquals( "Gone", summary.getAsJsonArray( "fetchFailures" ).get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
    }

    @Test
    void anEmptySourceIsRefusedOnStderrWithExitTwo() {
        assertEquals( 2, AclShapeAuditCli.run( source( Map.of() ), false, out, err ) );
        assertEquals( "REFUSED: the source lists no pages (empty or wrong --dir, or an empty wiki)\n", errors() );
        assertEquals( "", output() );
    }
}
