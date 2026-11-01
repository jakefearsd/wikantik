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
import com.wikantik.jdbc.SqlBinder;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Tombstones of hard-deleted logins ({@code deleted_logins}, V065). A tombstoned login is shown as "former member" and can
 * never be registered again, so a new account cannot inherit the old one's attribution. Rows are only ever added by the
 * application; releasing a name is an operator's {@code DELETE}.
 */
public final class DeletedLoginRepository {

    private static final String INSERT =
            "INSERT INTO deleted_logins ( login_name ) VALUES ( ? ) ON CONFLICT ( login_name ) DO NOTHING";
    private static final String EXISTS = "SELECT 1 FROM deleted_logins WHERE login_name = ?";
    private static final String ALL = "SELECT login_name FROM deleted_logins";

    private final Jdbc jdbc;

    public DeletedLoginRepository( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    /** Records {@code login} as deleted; recording it twice is a no-op. */
    public void record( final String login ) throws WikiSecurityException {
        try {
            jdbc.update( INSERT, ps -> ps.setString( 1, login ) );
        } catch ( final SQLException e ) {
            throw new WikiSecurityException( "Could not record the deleted login '" + login + "': " + e.getMessage(), e );
        }
    }

    /** Whether {@code login} was hard-deleted. */
    public boolean isDeleted( final String login ) throws WikiSecurityException {
        if ( login == null || login.isBlank() ) {
            return false;
        }
        try {
            return jdbc.queryOne( EXISTS, ps -> ps.setString( 1, login ), rs -> rs.getInt( 1 ) ).isPresent();
        } catch ( final SQLException e ) {
            throw new WikiSecurityException( "Could not check the deleted logins: " + e.getMessage(), e );
        }
    }

    /** Every tombstoned login. */
    public Set< String > all() throws WikiSecurityException {
        try {
            return new HashSet<>( jdbc.query( ALL, SqlBinder.NONE, rs -> rs.getString( 1 ) ) );
        } catch ( final SQLException e ) {
            throw new WikiSecurityException( "Could not list the deleted logins: " + e.getMessage(), e );
        }
    }
}
