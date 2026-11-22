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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The product hook: enabling, rotating, listing, and the signed-delivery check, over real PostgreSQL. */
@RequiresPostgres
class WorkCodeHostAuthPostgresTest {

    private static final Instant NOW = CodeHostFixture.START;
    private static final byte[] BODY = "{\"zen\":\"Keep it logically awesome.\"}".getBytes( StandardCharsets.UTF_8 );

    private CodeHostFixture f;
    private WorkCodeHostService hooks;
    private List< String[] > audits;

    @BeforeEach
    void setUp() throws SQLException {
        f = new CodeHostFixture();
        hooks = f.hooks;
        audits = f.audits;
        f.exec( "DELETE FROM work_product_event WHERE field = 'hook'" );
        f.exec( "DELETE FROM work_product_hook" );
    }

    private static String sign( final String secret, final byte[] body ) throws Exception {
        final Mac mac = Mac.getInstance( "HmacSHA256" );
        mac.init( new SecretKeySpec( secret.getBytes( StandardCharsets.UTF_8 ), "HmacSHA256" ) );
        return "sha256=" + HexFormat.of().formatHex( mac.doFinal( body ) );
    }

    @Test
    void anEnabledHookAuthenticatesItsOwnBody() throws Exception {
        final String secret = hooks.enableHook( f.admin, "wk" ).secret();
        final HookDelivery d = hooks.authenticate( "wk", "github", BODY, sign( secret, BODY ) ).orElseThrow();
        assertEquals( "wk", d.productKey() );
        assertEquals( "github", d.host() );
        assertEquals( "main", d.defaultBranch() );
        assertTrue( hooks.authenticate( "wk", "generic", BODY, sign( secret, BODY ) ).isPresent() );
    }

    @Test
    void everyRejectionIsEmpty() throws Exception {
        final String secret = hooks.enableHook( f.admin, "wk" ).secret();
        final String good = sign( secret, BODY );
        assertTrue( hooks.authenticate( "nothere", "github", BODY, good ).isEmpty(), "unknown product" );
        assertTrue( hooks.authenticate( "wk", "gitlab", BODY, good ).isEmpty(), "other host" );
        assertTrue( hooks.authenticate( "wk", "github", BODY, null ).isEmpty(), "no header" );
        assertTrue( hooks.authenticate( "wk", "github", BODY, sign( "nope", BODY ) ).isEmpty(), "wrong secret" );
        final byte[] changed = BODY.clone();
        changed[ 3 ] ^= 1;
        assertTrue( hooks.authenticate( "wk", "github", changed, good ).isEmpty(), "body changed by one byte" );
        hooks.disableHook( f.admin, "wk" );
        assertTrue( hooks.authenticate( "wk", "github", BODY, good ).isEmpty(), "disabled hook" );
    }

    @Test
    void noHookAndAnArchivedProductAreEmptyEvenWithTheDummyKeySignature() throws Exception {
        assertTrue( hooks.authenticate( "wk", "github", BODY, sign( CodeHostSecrets.DUMMY_KEY, BODY ) ).isEmpty(),
            "no hook: a signature under the fixed dummy key must not pass" );
        final String secret = hooks.enableHook( f.admin, "wk" ).secret();
        f.svc.archiveProduct( f.admin, "wk" );
        assertTrue( hooks.authenticate( "wk", "github", BODY, sign( secret, BODY ) ).isEmpty(), "archived" );
    }

    @Test
    void aRejectedDeliveryLeavesNothingRecorded() throws Exception {
        hooks.enableHook( f.admin, "wk" );
        assertTrue( hooks.authenticate( "wk", "github", BODY, sign( "wrong", BODY ) ).isEmpty() );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_product_hook WHERE last_received_at IS NOT NULL" ) );
    }

    @Test
    void rotationRetiresTheOldSecret() throws Exception {
        final String first = hooks.enableHook( f.admin, "wk" ).secret();
        final String second = hooks.enableHook( f.admin, "wk" ).secret();
        assertTrue( hooks.authenticate( "wk", "github", BODY, sign( first, BODY ) ).isEmpty() );
        assertTrue( hooks.authenticate( "wk", "github", BODY, sign( second, BODY ) ).isPresent() );
        assertEquals( List.of( "work.product.hook.enable", "work.product.hook.rotate" ),
            audits.stream().map( a -> a[ 1 ] ).toList() );
    }

    @Test
    void enablingWritesAProductEventAndAnAuditRecordWithoutTheSecret() throws Exception {
        final HookCreated created = hooks.enableHook( f.admin, "wk" );
        final String secret = created.secret();
        assertEquals( 1, f.count( "SELECT count(*) FROM work_product_event WHERE field = 'hook' AND to_value = 'enabled' "
            + "AND actor = 'admin'" ) );
        assertFalse( f.svc.productHistory( f.admin, "wk" ).toString().contains( secret ) );
        assertEquals( 0, f.count( "SELECT count(*) FROM work_product_event WHERE from_value LIKE '%" + secret
            + "%' OR to_value LIKE '%" + secret + "%'" ) );
        assertEquals( 1, audits.size() );
        final String[] audit = audits.get( 0 );
        assertEquals( "work.product.hook.enable", audit[ 1 ] );
        assertEquals( "work_product", audit[ 2 ] );
        assertEquals( "wk", audit[ 3 ] );
        assertEquals( null, audit[ 4 ] );
        assertFalse( String.join( "|", String.valueOf( audit[ 0 ] ), audit[ 1 ], audit[ 2 ], audit[ 3 ] ).contains( secret ) );
    }

    @Test
    void theSecretNeverAppearsInAnyToString() throws Exception {
        final HookCreated created = hooks.enableHook( f.admin, "wk" );
        final String secret = created.secret();
        assertFalse( created.toString().contains( secret ) );
        assertTrue( created.toString().contains( "secret=***" ) );
        assertFalse( created.status().toString().contains( secret ) );
        assertFalse( hooks.hooks( f.admin ).toString().contains( secret ) );
        final WorkCodeHostStore.Hook row = new JdbcWorkStore( f.ds ).notifyStores().codeHost().hook(
            f.svc.listProducts( f.admin ).stream().filter( p -> p.key().equals( "wk" ) ).findFirst().orElseThrow()
                .id() ).orElseThrow();
        assertEquals( secret, row.secret(), "stored as issued: an HMAC needs it" );
        assertFalse( row.toString().contains( secret ) );
        assertFalse( hooks.authenticate( "wk", "github", BODY, sign( secret, BODY ) ).orElseThrow()
            .toString().contains( secret ) );
    }

    @Test
    void aNonAdminCannotEnableDisableOrList() {
        assertThrows( WorkDeniedException.class, () -> hooks.enableHook( f.lena, "wk" ) );
        assertThrows( WorkDeniedException.class, () -> hooks.disableHook( f.lena, "wk" ) );
        assertThrows( WorkDeniedException.class, () -> hooks.hooks( f.lena ) );
        assertThrows( WorkDeniedException.class, () -> hooks.enableHook( f.author, "wk" ) );
        assertThrows( WorkNotFoundException.class, () -> hooks.enableHook( f.admin, "nothere" ) );
        assertThrows( WorkNotFoundException.class, () -> hooks.disableHook( f.admin, "nothere" ) );
    }

    @Test
    void anArchivedProductCannotGetAHook() throws SQLException {
        f.svc.archiveProduct( f.admin, "wk" );
        assertEquals( WorkConflictException.ARCHIVED,
            assertThrows( WorkConflictException.class, () -> hooks.enableHook( f.admin, "wk" ) ).reason() );
    }

    @Test
    void hooksShowsLastReceived() throws Exception {
        hooks.enableHook( f.admin, "wk" );
        assertEquals( NOW, hooks.hooks( f.admin ).get( "wk" ).enabledAt() );
        assertEquals( null, hooks.hooks( f.admin ).get( "wk" ).lastReceivedAt() );
        final long id = f.svc.listProducts( f.admin ).stream().filter( p -> p.key().equals( "wk" ) ).findFirst()
            .orElseThrow().id();
        new JdbcWorkStore( f.ds ).notifyStores().codeHost().received( id, Instant.parse( "2026-03-01T11:00:00Z" ) );
        final HookStatus status = hooks.hooks( f.admin ).get( "wk" );
        assertTrue( status.enabled() );
        assertEquals( Instant.parse( "2026-03-01T11:00:00Z" ), status.lastReceivedAt() );
    }

    @Test
    void disablingStampsTheHookAndAuditsOnce() throws Exception {
        hooks.enableHook( f.admin, "wk" );
        hooks.disableHook( f.admin, "wk" );
        hooks.disableHook( f.admin, "wk" );
        assertFalse( hooks.hooks( f.admin ).get( "wk" ).enabled() );
        assertEquals( 1, audits.stream().filter( a -> a[ 1 ].equals( "work.product.hook.disable" ) ).count() );
        assertEquals( 1, f.count( "SELECT count(*) FROM work_product_event WHERE field = 'hook' AND to_value = 'disabled'" ) );
    }
}
