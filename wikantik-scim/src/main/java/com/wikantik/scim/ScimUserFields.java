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
package com.wikantik.scim;

import com.google.gson.JsonObject;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.auth.sso.SSOAutoProvisionService;

import java.util.function.Consumer;

/**
 * Maps SCIM user attributes onto a {@link UserProfile}.
 *
 * <p>Pure field-mapping logic, deliberately kept off {@code ScimUserResource}: the servlet already
 * carries the HTTP surface for create/replace/patch/delete plus filtering and pagination, and folding
 * these branch-heavy mappers back into it pushes the class over the PMD {@code GodClass} and
 * {@code NPathComplexity} thresholds. Nothing here touches the servlet, the request, or the database —
 * only the profile and the parsed JSON — so it is also directly unit-testable without a servlet.</p>
 */
final class ScimUserFields {

    private ScimUserFields() {}

    /**
     * Applies the create/replace attributes shared by {@code POST /Users} and {@code PUT /Users/{id}}.
     * Each field is optional; a {@code null} leaves the existing profile value untouched.
     */
    static void applyCommonCreateFields( final UserProfile p, final ScimUserMapper.CreateFields f ) {
        if ( f.fullName() != null ) p.setFullname( f.fullName() );
        if ( f.email() != null ) p.setEmail( f.email() );
        if ( f.displayName() != null ) p.setWikiName( f.displayName() );
        if ( f.externalId() != null ) {
            p.getAttributes().put( SSOAutoProvisionService.ATTR_SSO_SUBJECT, f.externalId() );
        }
    }

    /**
     * Applies the simple (non-{@code active}) attributes of a SCIM PATCH onto {@code p}.
     *
     * <p>Each attribute is applied independently — the {@code |=} accumulation is deliberate and must
     * NOT become {@code ||}, which would short-circuit and silently skip later attributes once one had
     * already changed.</p>
     *
     * @return {@code true} if any attribute was changed, i.e. the profile needs saving.
     */
    static boolean applyPatchAttributes( final UserProfile p, final JsonObject attrs ) {
        boolean dirty = false;
        dirty |= assign( stringOrNull( attrs, "displayName" ), p::setWikiName );
        dirty |= assign( stringOrNull( objectOrNull( attrs, "name" ), "formatted" ), p::setFullname );
        dirty |= assign( firstEmail( attrs ), p::setEmail );
        dirty |= assign( stringOrNull( attrs, "externalId" ),
                v -> p.getAttributes().put( SSOAutoProvisionService.ATTR_SSO_SUBJECT, v ) );
        return dirty;
    }

    /** Applies {@code value} via {@code setter} when non-null; reports whether it did. */
    private static boolean assign( final String value, final Consumer< String > setter ) {
        if ( value == null ) return false;
        setter.accept( value );
        return true;
    }

    /** {@code o.key} as a string, or null when absent, JSON-null, or {@code o} itself is null. */
    private static String stringOrNull( final JsonObject o, final String key ) {
        if ( o == null || !o.has( key ) || o.get( key ).isJsonNull() ) return null;
        return o.get( key ).getAsString();
    }

    /** {@code o.key} as an object, or null when absent or not an object. */
    private static JsonObject objectOrNull( final JsonObject o, final String key ) {
        return o.has( key ) && o.get( key ).isJsonObject() ? o.getAsJsonObject( key ) : null;
    }

    /** The {@code value} of the first entry of the {@code emails} array, or null. */
    private static String firstEmail( final JsonObject attrs ) {
        if ( !attrs.has( "emails" ) || !attrs.get( "emails" ).isJsonArray() ) return null;
        final var arr = attrs.getAsJsonArray( "emails" );
        if ( arr.isEmpty() ) return null;
        return stringOrNull( arr.get( 0 ).getAsJsonObject(), "value" );
    }
}
