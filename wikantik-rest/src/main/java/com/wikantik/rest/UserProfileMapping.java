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
package com.wikantik.rest;

import com.wikantik.auth.user.UserProfile;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared core {@link UserProfile} field mapping, extracted from the byte-identical prefix
 * previously duplicated between {@code AdminUserResource#profileToMap} and
 * {@code AuthResource#profileToMap}. {@code AdminUserResource}'s version layers admin-only
 * fields (lastLogin, locked/lockExpiry, passwordMustChange) on top of this core; those extra
 * fields are NOT part of this shared helper — only the fields both callers emit identically.
 *
 * <p>{@code formatDate} is a {@code protected} instance method on {@link RestServletBase} (not
 * touched by this change), so callers pass their own {@code this::formatDate} reference rather
 * than this utility taking a dependency on the servlet base class.
 */
final class UserProfileMapping {

    private UserProfileMapping() {
    }

    /**
     * Builds the fields common to both callers, in the original field order: loginName,
     * fullName, email, bio, wikiName, created, lastModified. Returns a mutable,
     * insertion-ordered map so callers can append their own additional fields afterward.
     */
    static Map< String, Object > coreFields( final UserProfile profile,
                                              final Function< Date, String > dateFormatter ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "loginName", profile.getLoginName() );
        map.put( "fullName", profile.getFullname() );
        map.put( "email", profile.getEmail() );
        map.put( "bio", profile.getBio() );
        map.put( "wikiName", profile.getWikiName() );
        map.put( "created", dateFormatter.apply( profile.getCreated() ) );
        map.put( "lastModified", dateFormatter.apply( profile.getLastModified() ) );
        return map;
    }
}
