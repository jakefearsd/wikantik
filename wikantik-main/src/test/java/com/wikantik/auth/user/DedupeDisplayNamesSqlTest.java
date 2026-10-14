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
package com.wikantik.auth.user;

import com.wikantik.jdbc.testing.MigrationApplier;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code bin/db/one-shots/dedupe_display_names.sql} against a real PostgreSQL carrying every
 * migration. Each test runs inside one transaction that {@link #rollback()} discards, after
 * emptying {@code users} inside it, so the numbers the script picks ("Member 1", "Jake Fear 2")
 * depend only on the rows the test seeds and nothing leaks into other test classes sharing the
 * container.
 */
@RequiresPostgres
class DedupeDisplayNamesSqlTest {

    private Connection conn;

    @BeforeEach
    void begin() throws SQLException {
        conn = PostgresTestDb.createDataSource().getConnection();
        conn.setAutoCommit( false );
        try ( Statement s = conn.createStatement() ) {
            s.execute( "DELETE FROM users" );
        }
    }

    @AfterEach
    void rollback() throws SQLException {
        try {
            conn.rollback();
        } finally {
            conn.close();
        }
    }

    private void insertUser( final String login, final String fullName, final String wikiName, final String created )
            throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (uid, email, full_name, login_name, wiki_name, created) VALUES (?, ?, ?, ?, ?, ?)" ) ) {
            ps.setString( 1, "uid-" + login );
            ps.setString( 2, login + "@example.test" );
            ps.setString( 3, fullName );
            ps.setString( 4, login );
            ps.setString( 5, wikiName );
            ps.setTimestamp( 6, Timestamp.valueOf( created + " 00:00:00" ) );
            ps.executeUpdate();
        }
    }

    private void insertGroup( final String name ) throws SQLException {
        try ( PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO groups (name, created, modified) VALUES (?, now(), now())" ) ) {
            ps.setString( 1, name );
            ps.executeUpdate();
        }
    }

    private String script() {
        try {
            return Files.readString( MigrationApplier.findRepoRoot().resolve( "bin/db/one-shots/dedupe_display_names.sql" ) );
        } catch ( final IOException e ) {
            throw new UncheckedIOException( "cannot read dedupe_display_names.sql", e );
        }
    }

    /** Runs the script the way dedupe_display_names.sh does: with the apply setting, inside the open transaction. */
    private void runScript() throws SQLException {
        try ( Statement s = conn.createStatement() ) {
            s.execute( "SET LOCAL wikantik.dedupe_apply = 'on'" );
            s.execute( script() );
        }
    }

    private Map< String, String > displayNames() throws SQLException {
        final Map< String, String > out = new LinkedHashMap<>();
        try ( Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery( "SELECT login_name, full_name FROM users ORDER BY login_name" ) ) {
            while ( rs.next() ) {
                out.put( rs.getString( 1 ), rs.getString( 2 ) );
            }
        }
        return out;
    }

    private String scalar( final String sql ) throws SQLException {
        try ( Statement s = conn.createStatement(); ResultSet rs = s.executeQuery( sql ) ) {
            rs.next();
            return rs.getString( 1 );
        }
    }

    /** The report as "reason|old|new" lines, in the order the script made the changes. */
    private List< String > report() throws SQLException {
        final List< String > out = new ArrayList<>();
        try ( Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery(
                  "SELECT reason, old_display, new_display FROM dedupe_display_names_report ORDER BY seq" ) ) {
            while ( rs.next() ) {
                out.add( rs.getString( 1 ) + "|" + rs.getString( 2 ) + "|" + rs.getString( 3 ) );
            }
        }
        return out;
    }

    @Test
    void earliestAccountKeepsItsNameAndTheOthersAreNumberedSkippingTakenNumbers() throws SQLException {
        insertUser( "dup-a", "Jake Fear", "JakeFear", "2026-01-01" );
        insertUser( "dup-b", "jake fear", "JakeFear2", "2026-02-01" );
        insertUser( "dup-c", "  JAKE FEAR  ", "JakeFear3", "2026-03-01" );
        insertUser( "dup-d", "Jake Fear", "JakeFear4", "2026-04-01" );
        insertUser( "dup-e", "Jake Fear 3", "JakeFear33", "2026-05-01" );
        insertUser( "dup-f", "Alice", "Alice", "2026-06-01" );

        runScript();

        assertEquals( Map.of(
                "dup-a", "Jake Fear",
                "dup-b", "jake fear 2",
                "dup-c", "JAKE FEAR 4",
                "dup-d", "Jake Fear 5",
                "dup-e", "Jake Fear 3",
                "dup-f", "Alice" ), displayNames(),
            "the earliest keeps its name; later duplicates take the lowest free number (3 is held by dup-e)" );
        assertEquals( List.of(
                "duplicate|jake fear|jake fear 2",
                "duplicate|JAKE FEAR|JAKE FEAR 4",
                "duplicate|Jake Fear|Jake Fear 5" ), report() );
    }

    @Test
    void leavesWikiNamesAndUntouchedRowsAlone() throws SQLException {
        insertUser( "keep-a", "Sam Doe", "SamDoe", "2026-01-01" );
        insertUser( "keep-b", "Sam Doe", "SamDoe2", "2026-02-01" );
        insertUser( "keep-c", "Pat Roe", "PatRoe", "2026-03-01" );

        runScript();

        assertEquals( "SamDoe2", scalar( "SELECT wiki_name FROM users WHERE login_name = 'keep-b'" ),
            "wiki_name is not touched" );
        assertEquals( "Sam Doe 2", scalar( "SELECT full_name FROM users WHERE login_name = 'keep-b'" ) );
        assertNull( scalar( "SELECT modified FROM users WHERE login_name = 'keep-c'" ),
            "an account that needed no change keeps its modified timestamp (NULL in this fixture)" );
        assertNotNull( scalar( "SELECT modified FROM users WHERE login_name = 'keep-b'" ),
            "a renamed account's modified timestamp is bumped" );
    }

    @Test
    void blankNullAndEmailShapedNamesBecomeMemberNSkippingTakenNumbers() throws SQLException {
        insertUser( "blank-w", "Member 1", "Member1", "2026-01-01" );
        insertUser( "blank-x", "", "BlankX", "2026-02-01" );
        insertUser( "blank-y", null, "BlankY", "2026-03-01" );
        insertUser( "blank-z", "someone@example.test", "SomeoneAtExample", "2026-04-01" );
        insertUser( "blank-v", "   ", "BlankV", "2026-05-01" );

        runScript();

        assertEquals( Map.of(
                "blank-w", "Member 1",
                "blank-x", "Member 2",
                "blank-y", "Member 3",
                "blank-z", "Member 4",
                "blank-v", "Member 5" ), displayNames() );
        assertEquals( List.of(
                "blank|(blank)|Member 2",
                "blank|(blank)|Member 3",
                "email-shaped|(email-shaped)|Member 4",
                "blank|(blank)|Member 5" ), report() );
    }

    @Test
    void theReportCarriesDisplayNamesOnlyNeverLoginsOrEmails() throws SQLException {
        insertUser( "leaky@example.test", "leaky@example.test", "Leaky", "2026-01-01" );
        insertUser( "twin1@example.test", "Twin Name", "TwinName", "2026-02-01" );
        insertUser( "twin2@example.test", "Twin Name", "TwinName2", "2026-03-01" );

        runScript();

        final String everything = String.join( "\n", report() );
        assertFalse( everything.contains( "example.test" ), "no login or email in the report: " + everything );
        assertFalse( everything.contains( "leaky" ), "no login or email in the report: " + everything );
        assertEquals( List.of( "email-shaped|(email-shaped)|Member 1", "duplicate|Twin Name|Twin Name 2" ), report() );
    }

    @Test
    void aSecondRunChangesNothing() throws SQLException {
        insertUser( "idem-a", "Same Name", "SameName", "2026-01-01" );
        insertUser( "idem-b", "Same Name", "SameName2", "2026-02-01" );
        insertUser( "idem-c", "", "IdemC", "2026-03-01" );
        runScript();
        final Map< String, String > afterFirst = displayNames();
        assertFalse( report().isEmpty(), "fixture sanity: the first run changed something" );

        runScript();

        assertEquals( afterFirst, displayNames() );
        assertEquals( List.of(), report(), "nothing left to change" );
    }

    @Test
    void candidatesEqualToAnotherAccountsLoginOrAGroupNameAreSkipped() throws SQLException {
        insertUser( "skip-a", "Sam Doe", "SamDoe", "2026-01-01" );
        insertUser( "skip-b", "Sam Doe", "SamDoe2", "2026-02-01" );
        insertUser( "Sam Doe 2", "Someone Else", "SomeoneElse", "2026-03-01" );
        insertGroup( "sam doe 3" );

        runScript();

        assertEquals( "Sam Doe 4", scalar( "SELECT full_name FROM users WHERE login_name = 'skip-b'" ),
            "'Sam Doe 2' is another account's login and 'Sam Doe 3' is a group name" );
    }

    @Test
    void theSuffixIsAppendedWithinTheSixtyFourCharacterLimit() throws SQLException {
        final String longName = "b".repeat( 64 );
        insertUser( "long-a", longName, "LongA", "2026-01-01" );
        insertUser( "long-b", longName, "LongB", "2026-02-01" );

        runScript();

        final String renamed = scalar( "SELECT full_name FROM users WHERE login_name = 'long-b'" );
        assertEquals( 64, renamed.length() );
        assertEquals( "b".repeat( 62 ) + " 2", renamed );
    }

    @Test
    void refusesToRunWithoutTheApplySetting() throws SQLException {
        insertUser( "refuse-a", "Dup Name", "DupName", "2026-01-01" );
        insertUser( "refuse-b", "Dup Name", "DupName2", "2026-02-01" );
        final Savepoint before = conn.setSavepoint();

        final SQLException refused = assertThrows( SQLException.class, () -> {
            try ( Statement s = conn.createStatement() ) {
                s.execute( script() );
            }
        } );

        assertTrue( refused.getMessage().contains( "dedupe_display_names.sh" ), refused.getMessage() );
        conn.rollback( before );
        assertEquals( "Dup Name", displayNames().get( "refuse-b" ), "nothing was changed" );
    }

    /** The uid findings as "display name|problem" lines, in the order the script found them. */
    private List< String > uidReport() throws SQLException {
        final List< String > out = new ArrayList<>();
        try ( Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery(
                  "SELECT display_name, problem FROM dedupe_display_names_uid_report ORDER BY seq" ) ) {
            while ( rs.next() ) {
                out.add( rs.getString( 1 ) + "|" + rs.getString( 2 ) );
            }
        }
        return out;
    }

    @Test
    void whitespaceAndCaseVariantsAreOneGroupAndBlankUidsGetAStableId() throws Exception {
        insertUser( "a", "Jake Fear", "JakeFear", "2026-01-01" );
        insertUser( "b", "jake   FEAR", "JakeFear2", "2026-02-01" );
        try ( Statement s = conn.createStatement() ) {
            s.executeUpdate( "UPDATE users SET uid = NULL WHERE login_name = 'b'" );
        }
        runScript();
        try ( Statement s = conn.createStatement();
              ResultSet rs = s.executeQuery( "SELECT login_name, full_name, uid FROM users ORDER BY login_name" ) ) {
            rs.next(); assertEquals( "Jake Fear", rs.getString( 2 ) );
            // Own spelling, whitespace collapsed (what ProfileNameRules.displayNameForm yields), then the number.
            rs.next(); assertEquals( "jake FEAR 2", rs.getString( 2 ) );
            final String uid = rs.getString( 3 );
            assertNotNull( uid );
            assertTrue( uid.matches( "[A-Za-z0-9-]{1,64}" ) );
        }
        final String assigned = scalar( "SELECT uid FROM users WHERE login_name = 'b'" );
        assertEquals( List.of( "duplicate|jake FEAR|jake FEAR 2", "uid-backfill|1 account(s) without a stored uid|(new uid assigned)" ),
            report(), "the backfill is reported as a count, never as an account" );
        runScript();
        assertEquals( assigned, scalar( "SELECT uid FROM users WHERE login_name = 'b'" ),
            "a second run keeps the assigned uid" );
        assertEquals( List.of(), report(), "and has nothing left to report" );
    }

    @Test
    void emptyUidsAreBackfilledToo() throws SQLException {
        insertUser( "uid-empty", "Empty Uid", "EmptyUid", "2026-01-01" );
        insertUser( "uid-spaces", "Spaces Uid", "SpacesUid", "2026-02-01" );
        insertUser( "uid-fine", "Fine Uid", "FineUid", "2026-03-01" );
        try ( Statement s = conn.createStatement() ) {
            s.executeUpdate( "UPDATE users SET uid = '' WHERE login_name = 'uid-empty'" );
            s.executeUpdate( "UPDATE users SET uid = '   ' WHERE login_name = 'uid-spaces'" );
        }

        runScript();

        assertTrue( scalar( "SELECT uid FROM users WHERE login_name = 'uid-empty'" ).matches( "[A-Za-z0-9-]{1,64}" ) );
        assertTrue( scalar( "SELECT uid FROM users WHERE login_name = 'uid-spaces'" ).matches( "[A-Za-z0-9-]{1,64}" ) );
        assertEquals( "uid-uid-fine", scalar( "SELECT uid FROM users WHERE login_name = 'uid-fine'" ), "a stored uid is never replaced" );
        assertEquals( List.of( "uid-backfill|2 account(s) without a stored uid|(new uid assigned)" ), report() );
    }

    @Test
    void uidsThatCannotBeMentionTokensAreReportedByDisplayNameAndLeftAlone() throws SQLException {
        insertUser( "odd-a", "Odd Alpha", "OddAlpha", "2026-01-01" );
        insertUser( "odd-b", "Odd Beta", "OddBeta", "2026-02-01" );
        insertUser( "odd-c", "Odd Gamma", "OddGamma", "2026-03-01" );
        insertUser( "odd-d", "Odd Delta", "OddDelta", "2026-04-01" );
        try ( Statement s = conn.createStatement() ) {
            s.executeUpdate( "UPDATE users SET uid = 'has space' WHERE login_name = 'odd-a'" );
            s.executeUpdate( "UPDATE users SET uid = 'under_score' WHERE login_name = 'odd-b'" );
            s.executeUpdate( "UPDATE users SET uid = repeat( 'x', 65 ) WHERE login_name = 'odd-c'" );
            s.executeUpdate( "UPDATE users SET uid = '-6852820166199419346' WHERE login_name = 'odd-d'" );
        }

        runScript();

        assertEquals( List.of(
                "Odd Alpha|characters other than letters, digits and hyphen",
                "Odd Beta|characters other than letters, digits and hyphen",
                "Odd Gamma|longer than 64 characters" ), uidReport(),
            "display names only; a negative numeric uid (the V002 admin seed) is a valid shape" );
        assertEquals( "has space", scalar( "SELECT uid FROM users WHERE login_name = 'odd-a'" ), "reported, not changed" );
        assertEquals( List.of(), report(), "the name report is not mixed with uid findings" );
    }

    @Test
    void noBreakAndFullwidthSpacingVariantsAreOneGroupAndTheNewNameIsNormalised() throws SQLException {
        insertUser( "nfkc-a", "Jake Fear", "JakeFear", "2026-01-01" );
        insertUser( "nfkc-b", "Jake\u00A0Fear", "JakeFear2", "2026-02-01" );
        insertUser( "nfkc-c", "\uFF2Aake\u3000Fear", "JakeFear3", "2026-03-01" );

        runScript();

        assertEquals( Map.of(
                "nfkc-a", "Jake Fear",
                "nfkc-b", "Jake Fear 2",
                "nfkc-c", "Jake Fear 3" ), displayNames(),
            "NFKC turns a no-break or ideographic space into a plain one and a fullwidth letter into its ASCII form" );
    }

    @Test
    void aFullwidthAtSignIsEmailShapedLikeTheRealOne() throws SQLException {
        insertUser( "at-a", "someone\uFF20example.test", "SomeoneAt", "2026-01-01" );

        runScript();

        assertEquals( "Member 1", scalar( "SELECT full_name FROM users WHERE login_name = 'at-a'" ) );
        assertEquals( List.of( "email-shaped|(email-shaped)|Member 1" ), report() );
    }

    @Test
    void truncationNeverLeavesADoubledOrTrailingSpaceBeforeTheNumber() throws SQLException {
        // 61 letters, a space, then more: cutting at 62 characters leaves "...<61 x> " with a trailing space.
        final String longName = "c".repeat( 61 ) + " " + "d".repeat( 20 );
        insertUser( "cut-a", longName, "CutA", "2026-01-01" );
        insertUser( "cut-b", longName, "CutB", "2026-02-01" );

        runScript();

        assertEquals( "c".repeat( 61 ) + " 2", scalar( "SELECT full_name FROM users WHERE login_name = 'cut-b'" ) );
    }
}
