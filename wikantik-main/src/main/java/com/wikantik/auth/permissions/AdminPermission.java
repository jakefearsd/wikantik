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
package com.wikantik.auth.permissions;

import java.io.Serializable;
import java.security.Permission;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Permission to reach one functional area of the {@code /admin/*} surface.
 *
 * <p><strong>Why this exists.</strong> {@code /admin/*} spans 26 functional areas behind a single
 * {@link AllPermission} check, so any credential that can reach one can reach all of them --
 * including {@code connector-credentials} (the encrypted GitHub / Confluence / Drive secret store),
 * {@code apikeys} (mint new credentials) and {@code policy} (rewrite the permission model itself).
 * An integration that only needs to POST visibility rows should not hold that. This permission lets
 * a principal be granted exactly one area.</p>
 *
 * <p><strong>Strictly additive.</strong> {@link AllPermission} implies every
 * {@code AdminPermission}, so existing administrators are unaffected. This class only ever *adds* a
 * second way to pass the admin gate; it never removes the first. That property is load-bearing and
 * is asserted by {@code AdminPermissionTest#allPermissionImpliesAdminPermission} -- if
 * {@link PermissionChecks#isJSPWikiPermission} ever stops recognising this type,
 * {@code AllPermission.implies} silently returns false and *every admin is locked out of the whole
 * surface*. That is the single most dangerous failure mode here, which is why it has a dedicated
 * test rather than being left implicit.</p>
 *
 * <p>Target format is {@code wiki:area} (an unqualified value is treated as any-wiki), matching
 * {@link PagePermission} and {@link GroupPermission}. The only action today is
 * {@link #ACCESS_ACTION}; the action list is parsed as a set rather than a bitmask so a finer verb
 * (say {@code read} vs {@code write}) can be added later without a format change.</p>
 */
public final class AdminPermission extends Permission implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The only action defined today: may reach this admin area at all. */
    public static final String ACCESS_ACTION = "access";

    private static final String ACTION_SEPARATOR = ",";
    private static final String WILDCARD = "*";
    private static final String WIKI_SEPARATOR = ":";

    private final String wiki;
    private final String area;
    private final String actionString;
    private final Set< String > actions;

    /**
     * @param target  {@code area} or {@code wiki:area}; {@code *} matches any area
     * @param actions comma-separated action list, or {@code *} for every action
     */
    public AdminPermission( final String target, final String actions ) {
        super( target );
        if ( target == null || target.isBlank() ) {
            throw new IllegalArgumentException( "AdminPermission target is required" );
        }

        final String[] parts = target.split( WIKI_SEPARATOR );
        if ( parts.length >= 2 ) {
            this.wiki = parts[ 0 ].isEmpty() ? null : parts[ 0 ];
            this.area = normalise( parts[ 1 ] );
        } else {
            this.wiki = WILDCARD;
            this.area = normalise( parts[ 0 ] );
        }

        final Set< String > parsed = new LinkedHashSet<>();
        if ( actions != null && !actions.isBlank() ) {
            final String[] split = actions.toLowerCase( Locale.ROOT ).split( ACTION_SEPARATOR );
            Arrays.sort( split, String.CASE_INSENSITIVE_ORDER );
            for ( final String a : split ) {
                final String trimmed = a.strip();
                if ( !trimmed.isEmpty() ) {
                    parsed.add( trimmed );
                }
            }
        }
        this.actions = Collections.unmodifiableSet( parsed );
        this.actionString = String.join( ACTION_SEPARATOR, parsed );
    }

    private static String normalise( final String value ) {
        return value == null ? "" : value.strip().toLowerCase( Locale.ROOT );
    }

    /**
     * True when this permission covers everything {@code permission} asks for: same (or wildcard)
     * wiki, same (or wildcard) area, and a superset of the requested actions.
     *
     * <p>Deliberately narrow -- it implies only other {@code AdminPermission}s. Holding
     * {@code admin:insights} must never imply a page or wiki permission; the areas are a partition
     * of one servlet surface, not a hierarchy over the wiki.</p>
     */
    @Override
    public boolean implies( final Permission permission ) {
        if ( !( permission instanceof AdminPermission other ) ) {
            return false;
        }
        if ( !PagePermission.isSubset( wiki, other.wiki ) ) {
            return false;
        }
        if ( !WILDCARD.equals( area ) && !area.equals( other.area ) ) {
            return false;
        }
        return actions.contains( WILDCARD ) || actions.containsAll( other.actions );
    }

    @Override
    public String getActions() {
        return actionString;
    }

    /** The admin area this permission covers, e.g. {@code insights}. */
    public String getArea() {
        return area;
    }

    /** The wiki this permission is scoped to, or {@code *}. */
    public String getWiki() {
        return wiki;
    }

    @Override
    public boolean equals( final Object obj ) {
        if ( !( obj instanceof AdminPermission other ) ) {
            return false;
        }
        return actionString.equals( other.actionString )
                && area.equals( other.area )
                && ( wiki == null ? other.wiki == null : wiki.equals( other.wiki ) );
    }

    @Override
    public int hashCode() {
        return ( wiki == null ? 0 : wiki.hashCode() ) * 31 + area.hashCode() * 7 + actionString.hashCode();
    }

    @Override
    public String toString() {
        return "(\"" + getClass().getName() + "\",\"" + wiki + ":" + area + "\",\"" + actionString + "\")";
    }
}
