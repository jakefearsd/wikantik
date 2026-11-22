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

import com.wikantik.work.CodeHostTargets.Target;
import com.wikantik.work.CodeHostTargets.Targets;
import com.wikantik.work.WorkCodeHostStore.BranchFacts;
import com.wikantik.work.WorkCodeHostStore.DirectFacts;
import com.wikantik.work.WorkCodeHostStore.HostWrite;
import com.wikantik.work.WorkCodeHostStore.ReviewFacts;
import com.wikantik.work.WorkCodeHostStore.ReviewStep;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies one neutral {@link CodeEvent} to the item change records. People are resolved before any store call; each
 * named item gets its own transaction, and one item's refusal never stops another's write. Content the wiki cannot
 * use is {@link CodeHostOutcome#IGNORED}, never an exception; a database or directory failure propagates, so the
 * code host shows a failed delivery it can redeliver (P5-8).
 */
final class CodeHostApplier {

    private static final Logger LOG = LogManager.getLogger( CodeHostApplier.class );
    private static final Pattern SHA = Pattern.compile( "[0-9a-f]{7,64}" );
    private static final Pattern BRANCH = Pattern.compile( "[^\\s\\p{C}]{1," + ChangeInputs.MAX_BRANCH + "}" );
    private static final Pattern URL = Pattern.compile( "https?://[^\\s\\p{C}]{1," + ( ChangeInputs.MAX_URL - 8 ) + "}" );
    private static final Pattern NUMBER = Pattern.compile( "\\d{1,9}" );

    private final WorkStore store;
    private final WorkCodeHostStore hostStore;
    private final CodeHostTargets targets;
    private final CodeHostIdentities identities;
    private final Clock clock;
    private final Actor by;
    private boolean mismatchLogged;

    CodeHostApplier( final WorkStore store, final WorkDirectory directory, final Clock clock, final Actor by ) {
        this.store = store;
        this.hostStore = store.notifyStores().codeHost();
        this.targets = new CodeHostTargets( store );
        this.identities = new CodeHostIdentities( hostStore, directory );
        this.clock = clock;
        this.by = by;
    }

    /** One event's counts, summed over the items it names. */
    record Counts( int applied, int duplicate, int ignored ) {

        static final Counts NONE = new Counts( 0, 0, 0 );

        Counts plus( final CodeHostOutcome o ) {
            return switch ( o ) {
                case APPLIED -> new Counts( applied + 1, duplicate, ignored );
                case DUPLICATE -> new Counts( applied, duplicate + 1, ignored );
                case IGNORED -> new Counts( applied, duplicate, ignored + 1 );
            };
        }

        Counts plusIgnored( final int n ) {
            return new Counts( applied, duplicate, ignored + n );
        }

        /** The one outcome reported for the event: applied beats duplicate beats ignored. */
        CodeHostOutcome overall() {
            return applied > 0 ? CodeHostOutcome.APPLIED : duplicate > 0 ? CodeHostOutcome.DUPLICATE
                : CodeHostOutcome.IGNORED;
        }
    }

    Counts apply( final HookDelivery d, final CodeEvent raw ) throws SQLException {
        // every time this event carries is bounded here, once, before anything reads it (CodeHostTimes)
        final CodeEvent e = CodeHostTimes.bounded( raw, clock.instant() );
        if ( !CodeHostTargets.repoMatches( d, e ) ) {
            if ( !mismatchLogged ) {
                mismatchLogged = true;
                LOG.warn( "Code-host delivery for product {} ignored: repo_mismatch", d.productKey() );
            }
            return Counts.NONE.plusIgnored( 1 );
        }
        final Targets found = targets.items( d, e );
        Counts counts = Counts.NONE.plusIgnored( found.ignoredKeys() );
        if ( found.items().isEmpty() && found.ignoredKeys() == 0 ) {
            return counts.plusIgnored( 1 );
        }
        final Instant at = e.at();
        if ( at == null ) {
            // never stamped with the time it arrived: a replay would then be a new, later event (rule F)
            LOG.warn( "Code-host {} event for product {} ignored: no usable host time",
                e.kind().name().toLowerCase( Locale.ROOT ), d.productKey() );
            return counts.plusIgnored( found.items().size() );
        }
        for ( final Target t : found.items() ) {
            counts = counts.plus( applyTo( d, e, t, at ).outcome() );
        }
        return counts;
    }

    private HostWrite applyTo( final HookDelivery d, final CodeEvent e, final Target t, final Instant at )
            throws SQLException {
        if ( e.kind() == CodeEvent.Kind.PUSH ) {
            return CodeHostTargets.isDefaultBranchPush( d, e ) ? direct( d, e, t, at ) : branchPush( d, e, t, at );
        }
        return e.kind() == CodeEvent.Kind.REVIEW_MERGED ? merge( d, e, t, at ) : review( d, e, t, at );
    }

    /** A push to the default branch: the commits that name this item become one direct change (P5-1). */
    private HostWrite direct( final HookDelivery d, final CodeEvent e, final Target t, final Instant at )
            throws SQLException {
        final String after = sha( e.after() );
        if ( after == null || isZeros( after ) ) {
            return HostWrite.none( CodeHostOutcome.IGNORED );
        }
        final List< CodeEvent.Commit > naming = e.commits().stream().filter( CodeEvent.Commit::distinct )
            .filter( c -> ItemKeyScan.keys( c.message() ).contains( t.item().key() ) ).toList();
        final Set< String > shas = new LinkedHashSet<>();
        final Set< String > numbers = new LinkedHashSet<>();
        for ( final CodeEvent.Commit c : e.commits() ) {
            if ( sha( c.sha() ) != null ) {
                shas.add( sha( c.sha() ) );
            }
            numbers.addAll( ItemKeyScan.reviewNumbers( c.message() ) );
        }
        final String head = naming.isEmpty() || sha( naming.get( naming.size() - 1 ).sha() ) == null ? after
            : sha( naming.get( naming.size() - 1 ).sha() );
        final Instant first = naming.stream().map( CodeEvent.Commit::at ).filter( java.util.Objects::nonNull )
            .min( Comparator.naturalOrder() ).orElse( null );
        final CodeHostWho author = pusher( d, e, naming );
        final DirectFacts facts = new DirectFacts( e.branch(), after, head, first, shas, numbers, author, at );
        return hostStore.direct( d.productId(), t.item(), facts, CodeHostFlags.forMerge( MergeKind.DIRECT, t.policy() ),
            by );
    }

    /**
     * Who a push is attributed to: the pusher the host asserts ({@code e.actor()}), through the identity mapping, and
     * never a commit's author, whose address anyone with push rights can write (M-1). An unmapped pusher is no wiki
     * user (shown by their host login); no pusher is {@link CodeHostWho#UNKNOWN}. The commits' authors only teach the
     * username mapping, first, so a pusher whose own commits are in this push is recognised.
     */
    private CodeHostWho pusher( final HookDelivery d, final CodeEvent e, final List< CodeEvent.Commit > commits )
            throws SQLException {
        for ( final CodeEvent.Commit c : commits ) {
            identities.teach( d.host(), c.author() );
        }
        return identities.resolve( d.host(), e.actor() );
    }

    /** A pull request's merge; the merge commit is the one the host reports, else the head that was merged. */
    private HostWrite merge( final HookDelivery d, final CodeEvent e, final Target t, final Instant at )
            throws SQLException {
        final ReviewFacts facts = facts( d, e, at );
        if ( facts == null ) {
            return HostWrite.none( CodeHostOutcome.IGNORED );
        }
        final String mergedSha = sha( e.mergedSha() ) != null ? sha( e.mergedSha() ) : facts.headSha();
        if ( mergedSha == null ) {
            return HostWrite.none( CodeHostOutcome.IGNORED );
        }
        return hostStore.merge( d.productId(), t.item(), facts, mergedSha, identities.resolve( d.host(), e.actor() ),
            t.policy(), by );
    }

    private HostWrite branchPush( final HookDelivery d, final CodeEvent e, final Target t, final Instant at )
            throws SQLException {
        final String head = sha( e.after() );
        if ( head == null || !branchOk( e.branch() ) || isZeros( head ) ) {
            return HostWrite.none( CodeHostOutcome.IGNORED );
        }
        final List< CodeEvent.Commit > distinct = e.commits().stream().filter( CodeEvent.Commit::distinct ).toList();
        final Instant first = distinct.stream().map( CodeEvent.Commit::at ).filter( java.util.Objects::nonNull )
            .min( Comparator.naturalOrder() ).map( c -> c.isAfter( at ) ? at : c ).orElse( null );
        final CodeHostWho opener = pusher( d, e, distinct );
        final String base = CodeHostTargets.defaultBranch( d, e );
        return hostStore.branchPush( d.productId(), t.item(),
            new BranchFacts( e.branch(), base == null ? "main" : base, head, first, opener, at ), by );
    }

    private HostWrite review( final HookDelivery d, final CodeEvent e, final Target t, final Instant at )
            throws SQLException {
        final ReviewStep step = step( e.kind(), e.review() );
        final ReviewFacts facts = step == null ? null : facts( d, e, at );
        if ( facts == null ) {
            return HostWrite.none( CodeHostOutcome.IGNORED );
        }
        final CodeHostWho reviewer = step == ReviewStep.APPROVED ? identities.resolve( d.host(), e.actor() )
            : CodeHostWho.UNKNOWN;
        return hostStore.review( d.productId(), t.item(), facts, step, reviewer, by );
    }

    /** The pull request facts of an event; null when the event names no usable pull request or branch. */
    private ReviewFacts facts( final HookDelivery d, final CodeEvent e, final Instant at ) throws SQLException {
        final CodeEvent.Review r = e.review();
        if ( r == null || r.number() == null || !NUMBER.matcher( r.number() ).matches() || !branchOk( e.branch() ) ) {
            return null;
        }
        final String base = e.baseBranch() != null && branchOk( e.baseBranch() ) ? e.baseBranch()
            : CodeHostTargets.defaultBranch( d, e );
        final String url = r.url() != null && URL.matcher( r.url().trim() ).matches() ? r.url().trim() : null;
        return new ReviewFacts( r.number(), url, e.branch(), base == null ? "main" : base, sha( r.headSha() ), at,
            identities.resolve( d.host(), r.author() ) );
    }

    private static ReviewStep step( final CodeEvent.Kind kind, final CodeEvent.Review r ) {
        return switch ( kind ) {
            case REVIEW_OPENED -> r != null && r.draft() ? ReviewStep.OPENED_DRAFT : ReviewStep.OPENED;
            case REVIEW_READY -> ReviewStep.READY;
            case REVIEW_UPDATED -> ReviewStep.UPDATED;
            case REVIEW_APPROVED -> ReviewStep.APPROVED;
            case REVIEW_CLOSED -> ReviewStep.CLOSED;
            case REVIEW_REOPENED -> ReviewStep.REOPENED;
            default -> null;
        };
    }

    private static boolean branchOk( final String branch ) {
        return branch != null && BRANCH.matcher( branch ).matches();
    }

    private static String sha( final String raw ) {
        if ( raw == null ) {
            return null;
        }
        final String lower = raw.trim().toLowerCase( Locale.ROOT );
        return SHA.matcher( lower ).matches() ? lower : null;
    }

    private static boolean isZeros( final String sha ) {
        return sha.chars().allMatch( c -> c == '0' );
    }

    WorkStore store() {
        return store;
    }
}
