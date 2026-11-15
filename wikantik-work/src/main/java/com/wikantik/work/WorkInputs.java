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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Input rules for {@link WorkService}: what a name, key, link or comment may be. Pure: no storage. */
final class WorkInputs {

    private static final Pattern PROJECT_KEY = Pattern.compile( "^[A-Z][A-Z0-9]{1,9}$" );
    private static final Pattern PRODUCT_KEY = Pattern.compile( "^[a-z][a-z0-9-]{1,39}$" );
    private static final Pattern MENTION = Pattern.compile( "(?<![\\w@.])@([A-Za-z0-9][A-Za-z0-9._-]*)" );
    private static final Set< String > LINK_KINDS = Set.of( "page", "issue", "url", "blocked_by", "commit" );
    private static final Set< String > PRINCIPAL_KINDS = Set.of( "user", "group", "agent" );
    private static final int DEFAULT_ACTIVITY = 50;
    private static final int MAX_ACTIVITY = 200;
    private static final int MAX_LINK_TARGET = 2000;

    private WorkInputs() {
    }

    static void requireName( final String name ) {
        if ( name == null || name.isBlank() ) {
            throw new WorkValidationException( "name", "A name is required." );
        }
    }

    static void requireProjectKey( final String key ) {
        if ( key == null || !PROJECT_KEY.matcher( key ).matches() ) {
            throw new WorkValidationException( "key", "A project key is 2 to 10 capital letters or digits, "
                + "starting with a letter." );
        }
    }

    static void requireMember( final String principal, final String principalKind, final WorkRole role ) {
        if ( principal == null || principal.isBlank() ) {
            throw new WorkValidationException( "principal", "A member needs a name." );
        }
        if ( principalKind == null || !PRINCIPAL_KINDS.contains( principalKind ) ) {
            throw new WorkValidationException( "principalKind", "principalKind must be user, group or agent." );
        }
        if ( role == null ) {
            throw new WorkValidationException( "role", "A member needs a role." );
        }
    }

    /** The validated product a draft describes, with the autonomy ceiling and deploy gate defaulted. */
    static Product newProduct( final Product draft ) {
        if ( draft.key() == null || !PRODUCT_KEY.matcher( draft.key() ).matches() ) {
            throw new WorkValidationException( "key", "A product key is lowercase letters, digits and dashes." );
        }
        requireName( draft.name() );
        return new Product( 0, draft.key(), draft.name().trim(), draft.repoUrl(), draft.defaultBranch(),
            draft.wikiCluster(), draft.autonomyCeiling() == null ? Autonomy.DELEGATED : draft.autonomyCeiling(),
            draft.deployGate() == null ? DeployGate.HUMAN : draft.deployGate(), null );
    }

    /** {@code existing} with the editable fields of {@code product}; an unset ceiling or gate is kept. */
    static Product updatedProduct( final Product existing, final Product product ) {
        requireName( product.name() );
        return new Product( existing.id(), existing.key(), product.name().trim(), product.repoUrl(),
            product.defaultBranch(), product.wikiCluster(),
            product.autonomyCeiling() == null ? existing.autonomyCeiling() : product.autonomyCeiling(),
            product.deployGate() == null ? existing.deployGate() : product.deployGate(), existing.archivedAt() );
    }

    static void requireBody( final String body ) {
        if ( body == null || body.isBlank() ) {
            throw new WorkValidationException( "body", "A comment cannot be empty." );
        }
    }

    /** The link target as it is stored: trimmed, after checking the kind and the target's shape. */
    static String linkTarget( final String kind, final String target ) {
        if ( kind == null || !LINK_KINDS.contains( kind ) ) {
            throw new WorkValidationException( "kind", "kind must be one of " + new TreeSet<>( LINK_KINDS ) );
        }
        if ( target == null || target.isBlank() || target.length() > MAX_LINK_TARGET
                || "url".equals( kind ) && !target.matches( "(?i)^https?://\\S+$" ) ) {
            throw new WorkValidationException( "target", "target is missing, too long, or not an http(s) address." );
        }
        return target.trim();
    }

    /** {@code @login} tokens in the body, de-duplicated, in order. */
    static Set< String > mentions( final String body ) {
        final Set< String > logins = new LinkedHashSet<>();
        final Matcher m = MENTION.matcher( body );
        while ( m.find() ) {
            logins.add( m.group( 1 ).replaceAll( "[.\\-_]+$", "" ) );
        }
        return logins;
    }

    static int activityLimit( final int requested ) {
        return requested <= 0 ? DEFAULT_ACTIVITY : Math.min( requested, MAX_ACTIVITY );
    }

    /** The item as {@code version} says the caller last saw it: the optimistic-locking stamp. */
    static Item withVersion( final Item i, final int version ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), version, i.createdAt(), i.updatedAt(), i.closedAt() );
    }

    /** Keeps only the logins {@code isMember} accepts. */
    static List< String > ofMembers( final Set< String > logins, final MemberTest isMember ) throws SQLException {
        final List< String > kept = new ArrayList<>();
        for ( final String login : logins ) {
            if ( !login.isEmpty() && isMember.test( login ) ) {
                kept.add( login );
            }
        }
        return kept;
    }

    @FunctionalInterface
    interface MemberTest {
        boolean test( String login ) throws SQLException;
    }
}
