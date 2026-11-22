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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Input rules for {@link WorkService}: what a name, key, link or comment may be. Pure: no storage. */
final class WorkInputs {

    private static final Pattern PROJECT_KEY = Pattern.compile( "^[A-Z][A-Z0-9]{1,9}$" );
    private static final Pattern PRODUCT_KEY = Pattern.compile( "^[a-z][a-z0-9-]{1,39}$" );
    private static final Pattern QUEUE_KEY = Pattern.compile( "^[a-z][a-z0-9-]{1,29}$" );
    /** Group 1 the scheme, group 2 the userinfo (the part before the {@code @} in the authority). */
    private static final Pattern REPO_URL_USERINFO = Pattern.compile( "^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)@" );
    private static final Set<String> SSH_SCHEMES = Set.of( "ssh", "git+ssh", "ssh+git" );
    private static final Pattern AGENT_NAME =
        Pattern.compile( "^[a-z0-9][a-z0-9._-]{0,39}(@[a-z0-9][a-z0-9.-]{0,39})?$" );
    private static final Set< String > LINK_KINDS = Set.of( "page", "issue", "url", "blocked_by", "commit" );
    private static final Set< String > PRINCIPAL_KINDS = Set.of( "user", "group", "agent" );
    /** First segments of SPA routes under {@code /work/}: a project with one of these keys could not be opened. */
    private static final Set< String > RESERVED_KEYS = Set.of( "ITEM", "PRODUCTS", "AGENTS", "LOG", "DEPLOYMENTS", "INBOX" );
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

    static void requireQueueKey( final String key ) {
        if ( key == null || !QUEUE_KEY.matcher( key ).matches() ) {
            throw new WorkValidationException( "key", "A queue key is 2 to 30 lowercase letters, digits and dashes, "
                + "starting with a letter." );
        }
    }

    static void requireAgentName( final String name ) {
        if ( name == null || !AGENT_NAME.matcher( name ).matches() ) {
            throw new WorkValidationException( "name", "An agent name is lowercase letters, digits, dots, dashes and "
                + "underscores, optionally followed by @host." );
        }
    }

    static void requireAgentRole( final WorkRole role ) {
        if ( role == WorkRole.LEAD ) {
            throw new WorkValidationException( "role", "An agent's role is viewer or member." );
        }
    }

    /** A key the work pages use for something else ({@code /work/item/…}, {@code /work/products}), any case. */
    static void requireUnreservedKey( final String key ) {
        if ( key != null && RESERVED_KEYS.contains( key.toUpperCase( Locale.ROOT ) ) ) {
            throw new WorkValidationException( "key", "That key is reserved; choose another." );
        }
    }

    /** A member to add or change. An agent member is validated against the registry by the service. */
    static void requireMember( final String principal, final String principalKind, final WorkRole role ) {
        requireMemberRef( principal, principalKind );
        if ( role == null ) {
            throw new WorkValidationException( "role", "A member needs a role." );
        }
    }

    /** The principal and kind that name a member. */
    static void requireMemberRef( final String principal, final String principalKind ) {
        if ( principal == null || principal.isBlank() ) {
            throw new WorkValidationException( "principal", "A member needs a name." );
        }
        if ( principalKind == null || !PRINCIPAL_KINDS.contains( principalKind ) ) {
            throw new WorkValidationException( "principalKind", "principalKind must be user, group or agent." );
        }
    }

    /** The validated product a draft describes, with the autonomy ceiling and deploy gate defaulted. */
    static Product newProduct( final Product draft ) {
        if ( draft.key() == null || !PRODUCT_KEY.matcher( draft.key() ).matches() ) {
            throw new WorkValidationException( "key", "A product key is lowercase letters, digits and dashes." );
        }
        requireName( draft.name() );
        requireNoCredentials( draft.repoUrl() );
        return new Product( 0, draft.key(), draft.name().trim(), draft.repoUrl(), draft.defaultBranch(),
            draft.wikiCluster(), draft.autonomyCeiling() == null ? Autonomy.DELEGATED : draft.autonomyCeiling(),
            draft.deployGate() == null ? DeployGate.HUMAN : draft.deployGate(), null );
    }

    /** A repository URL is shown in lists and kept in the append-only history, so it must carry no user name or password (an ssh user name alone is allowed). */
    private static void requireNoCredentials( final String repoUrl ) {
        if ( repoUrl == null ) {
            return;
        }
        final Matcher m = REPO_URL_USERINFO.matcher( repoUrl.trim() );
        // ssh://git@host is the conventional form: a bare user name there is not a secret, a password always is
        if ( m.find() && !( SSH_SCHEMES.contains( m.group( 1 ).toLowerCase( Locale.ROOT ) ) && m.group( 2 ).indexOf( ':' ) < 0 ) ) {
            throw new WorkValidationException( "repoUrl", "Leave the user name and password out of the repository URL." );
        }
    }

    /** {@code existing} with the editable fields of {@code product}; an unset ceiling or gate is kept. */
    static Product updatedProduct( final Product existing, final Product product ) {
        requireName( product.name() );
        requireNoCredentials( product.repoUrl() );
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

    /**
     * Checks a {@code types} filter against {@code allowed}; null or empty means no filter. Independent of any project,
     * so a bad filter is refused identically whether or not the project exists or is visible.
     */
    static void requireEventTypes( final Set< String > types, final Set< String > allowed ) {
        if ( types == null ) {
            return;
        }
        for ( final String type : types ) {
            if ( !allowed.contains( type ) ) {
                throw new WorkValidationException( "types", "types must be among " + new TreeSet<>( allowed ) + "." );
            }
        }
    }

    /**
     * The reasons the system itself records on a {@code released} event. A person's own release note may not be
     * one of them (after trimming, any case): the stored note has no other discriminator, and a note that read as
     * a consequence would lose the person's words.
     */
    static final Set< String > RELEASE_REASONS = Set.of( "moved", "reassigned", "access removed", "agent retired",
        "project archived", "sponsor access removed" );

    static void requireReleaseNote( final String note ) {
        if ( note != null && RELEASE_REASONS.contains( note.trim().toLowerCase( Locale.ROOT ) ) ) {
            throw new WorkValidationException( "note", "Choose a different note." );
        }
    }

    static int activityLimit( final int requested ) {
        return requested <= 0 ? DEFAULT_ACTIVITY : Math.min( requested, MAX_ACTIVITY );
    }

    /** The item as {@code version} says the caller last saw it: the optimistic-locking stamp. */
    static Item withVersion( final Item i, final int version ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), i.status(), i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), version, i.createdAt(), i.updatedAt(), i.closedAt(),
            i.queue(), i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(),
            i.type(), i.labels(), i.sprintId(), i.estimate() );
    }

    /** Keeps only the logins {@code isMember} accepts. */
    static List< String > ofMembers( final Collection< String > logins, final MemberTest isMember )
            throws SQLException {
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
