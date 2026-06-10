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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fresh-install "must change password" bootstrap relies on V002, V039 and
 * seed-users.sql all carrying the SAME canonical admin/admin123 hash: V039's
 * backstop UPDATE only fires when the stored hash equals the seeded literal.
 * If any copy drifts, a fresh install silently stops flagging the default
 * admin. This test pins the three SQL files to the canonical literal.
 */
class SeedCredentialDriftTest {

    private static final String CANONICAL_ADMIN_HASH =
            "{SHA-256}zij+qwD6JAlf9h+1tHdjNWTgqDASCJxt4y70G64C9PIoxFO2Lyq/xg==";

    private static Path repoRoot() {
        Path dir = Paths.get( System.getProperty( "user.dir" ) ).toAbsolutePath();
        while ( dir != null && !Files.isDirectory( dir.resolve( "bin/db/migrations" ) ) ) {
            dir = dir.getParent();
        }
        if ( dir == null ) {
            throw new IllegalStateException( "Could not locate repo root (bin/db/migrations) above " + System.getProperty( "user.dir" ) );
        }
        return dir;
    }

    private static String read( final String relative ) throws IOException {
        return Files.readString( repoRoot().resolve( relative ) );
    }

    @Test
    void v002SeedsTheCanonicalAdminHash() throws IOException {
        assertTrue( read( "bin/db/migrations/V002__core_users_groups.sql" ).contains( CANONICAL_ADMIN_HASH ),
                "V002 admin seed must use the canonical admin123 hash" );
    }

    @Test
    void v039BackstopTargetsTheCanonicalAdminHash() throws IOException {
        assertTrue( read( "bin/db/migrations/V039__password_must_change.sql" ).contains( CANONICAL_ADMIN_HASH ),
                "V039 backstop must match the hash V002 seeds, or fresh installs stop being flagged" );
    }

    @Test
    void seedUsersSqlUsesTheCanonicalAdminHash() throws IOException {
        assertTrue( read( "bin/db/seed-users.sql" ).contains( CANONICAL_ADMIN_HASH ),
                "seed-users.sql admin seed must use the canonical admin123 hash" );
    }
}
