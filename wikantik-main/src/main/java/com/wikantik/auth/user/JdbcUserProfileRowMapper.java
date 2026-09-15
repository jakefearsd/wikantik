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

import org.apache.commons.lang3.StringUtils;
import com.wikantik.util.Serializer;

import java.io.IOException;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.Map;

/**
 * {@code users} row ↔ {@link UserProfile} mapping for {@link JDBCUserDatabase}: builds a
 * {@code UserProfile} from a {@link ResultSet} row, and binds a {@code UserProfile}'s fields
 * onto the parameters of an insert/update {@link PreparedStatement}. Split out of
 * {@code JDBCUserDatabase} purely to keep that class's design-complexity metrics in check —
 * no behaviour changed, including the exact log messages emitted on a malformed row.
 *
 * @see JDBCUserDatabase
 */
final class JdbcUserProfileRowMapper {

    /** Supplies {@link UserDatabase#newProfile()} and, transitively, UID generation. */
    private final UserDatabase owningDatabase;

    JdbcUserProfileRowMapper( final UserDatabase owningDatabase ) {
        this.owningDatabase = owningDatabase;
    }

    /** Maps the current row of {@code rs} onto a freshly-created {@link UserProfile}. */
    UserProfile mapProfileRow( final ResultSet rs ) throws SQLException {
        final UserProfile profile = owningDatabase.newProfile();

        // Fetch the basic user attributes
        profile.setUid( rs.getString( "uid" ) );
        if ( profile.getUid() == null ) {
            profile.setUid( AbstractUserDatabase.generateUid( owningDatabase ) );
        }
        profile.setCreated( rs.getTimestamp( "created" ) );
        profile.setEmail( rs.getString( "email" ) );
        profile.setFullname( rs.getString( "full_name" ) );
        profile.setLastModified( rs.getTimestamp( "modified" ) );
        profile.setLastLogin( rs.getTimestamp( "last_login" ) );
        final Date lockExpiryDate = rs.getDate( "lock_expiry" );
        profile.setLockExpiry( rs.wasNull() ? null : lockExpiryDate );
        profile.setLoginName( rs.getString( "login_name" ) );
        profile.setPassword( rs.getString( "password" ) );
        profile.setBio( rs.getString( "bio" ) );
        profile.setPasswordMustChange( rs.getBoolean( "password_must_change" ) );

        // Fetch the user attributes. A blank value (as opposed to a genuinely corrupt one) is
        // not a parse failure - it just means the row has no attributes - so it is skipped
        // quietly rather than being handed to the deserializer, which would otherwise blow up
        // with an EOFException on every single request from an account whose row has this shape.
        final String rawAttributes = rs.getString( "attributes" );
        if ( StringUtils.isNotBlank( rawAttributes ) ) {
            try {
                final Map<String,? extends Serializable> userAttributes = Serializer.deserializeFromBase64( rawAttributes );
                profile.getAttributes().putAll( userAttributes );
            } catch ( final IOException e ) {
                AbstractUserDatabase.LOG.error( "Could not parse user profile attributes for login '{}'!",
                        describeLoginNameForLog( rs ), e );
            }
        }
        return profile;
    }

    /**
     * Reads {@code login_name} from {@code rs} for use in a diagnostic log message, never
     * letting a failure to read it (or a null value) throw out of the diagnostic itself.
     */
    private static String describeLoginNameForLog( final ResultSet rs ) {
        try {
            final String loginName = rs.getString( "login_name" );
            return loginName != null ? loginName : "<unknown>";
        } catch ( final SQLException e ) {
            return "<unknown>";
        }
    }

    /** Maps the current {@code users} row's {@code wiki_name}, or {@code null} (logged) when it is null/empty. */
    String mapWikiNameOrWarnAndSkip( final ResultSet rs ) throws SQLException {
        final String wikiNameValue = rs.getString( "wiki_name" );
        if( StringUtils.isEmpty( wikiNameValue ) ) {
            AbstractUserDatabase.LOG.warn( "Detected null or empty wiki name for {} in JDBCUserDataBase. Check your user database.",
                    rs.getString( "login_name" ) );
            return null;
        }
        return wikiNameValue;
    }

    /** {@link #mapProfileRow} unless {@code wiki_name} is null/empty, in which case {@code null} (logged, mirroring {@link #mapWikiNameOrWarnAndSkip}). */
    UserProfile mapProfileRowOrSkipEmptyWikiName( final ResultSet rs ) throws SQLException {
        final String wikiNameValue = rs.getString( "wiki_name" );
        if ( StringUtils.isEmpty( wikiNameValue ) ) {
            AbstractUserDatabase.LOG.warn( "Detected null or empty wiki name for {} in JDBCUserDataBase. Check your user database.",
                    rs.getString( "login_name" ) );
            return null;
        }
        return mapProfileRow( rs );
    }

    /**
     * Sets the common profile parameters (1-9) on a PreparedStatement for both insert and update operations.
     */
    void setProfileParameters( final PreparedStatement ps, final UserProfile profile,
                                final String password, final Timestamp ts ) throws SQLException {
        ps.setString( 1, profile.getUid() );
        ps.setString( 2, profile.getEmail() );
        ps.setString( 3, profile.getFullname() );
        ps.setString( 4, password );
        ps.setString( 5, profile.getWikiName() );
        ps.setTimestamp( 6, ts );
        ps.setString( 7, profile.getLoginName() );
        try {
            ps.setString( 8, Serializer.serializeToBase64( profile.getAttributes() ) );
        } catch ( final IOException e ) {
            throw new SQLException( "Could not save user profile attribute. Reason: " + e.getMessage(), e );
        }
        ps.setString( 9, profile.getBio() );
    }
}
