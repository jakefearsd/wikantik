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

import com.wikantik.auth.WikiSecurityException;
import com.wikantik.jdbc.Jdbc;

import java.sql.SQLException;
import java.util.List;

/** The query behind {@link UserDatabase#loginsForEmailIgnoreCase}, kept out of {@link JDBCUserDatabase} to hold its size. */
final class LoginsForEmail {

    private static final String SQL =
            "SELECT login_name FROM users WHERE lower(btrim(email)) = lower(?) ORDER BY login_name LIMIT 2";

    private LoginsForEmail() {
    }

    /** At most two logins whose address equals {@code email} ignoring case and surrounding blanks; none for a blank address. */
    static List< String > find( final Jdbc jdbc, final String email ) throws WikiSecurityException {
        if ( email == null || email.isBlank() ) {
            return List.of();
        }
        try {
            return jdbc.query( SQL, ps -> ps.setString( 1, email.trim() ), rs -> rs.getString( 1 ) );
        } catch ( final SQLException e ) {
            throw new WikiSecurityException( "Could not look up accounts by mail address: " + e.getMessage(), e );
        }
    }
}
