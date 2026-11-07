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
package com.wikantik.collab;

import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import com.wikantik.collab.text.TextDiff;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Section 7.3, publish, as amended by A1 (reconcile by content hash before the save), A2 (the pending-publish marker
 * and its recovery rule, E39), A3 (external changes queue during the save) and the Phase 1 publish details (meta ops
 * before the base change, a snapshot of the live head, the base's original typed meta values). Runs with the session's
 * base lock held and takes the session lock only around state: the page-store reads and the diff N happen outside it,
 * and the page save itself runs with neither lock held (the caller does it between {@link #start} and
 * {@link #finish}/{@link #failed}).
 */
final class PublishFlow {

    private static final Logger LOG = LogManager.getLogger( PublishFlow.class );
    /** The message of the save adapter's page-gone conflict; the single source is {@link VersionConflictException#PAGE_GONE}. */
    static final String PAGE_GONE = VersionConflictException.PAGE_GONE;

    private final Draft d;
    private final ReentrantLock lock;
    private final CollabPageWriter writer;
    private final CollabIdentity identity;
    private final Answers answers;
    private final ExternalMerge merge;
    /** A finished publish is audited, among the completions (guarded: never throws). */
    private final CollabAudit audit;

    PublishFlow( final Draft d, final ReentrantLock lock, final CollabPageWriter writer, final CollabIdentity identity,
                 final Answers answers, final ExternalMerge merge, final CollabAudit audit ) {
        this.d = d;
        this.lock = lock;
        this.writer = writer;
        this.identity = identity;
        this.answers = answers;
        this.merge = merge;
        this.audit = audit;
    }

    /** A publish answered before any save. */
    PublishStart answered( final int status, final String reason ) {
        return answered( status, reason, null );
    }

    /** A publish answered before any save, with a detail (403 {@code evicted}). */
    PublishStart answered( final int status, final String reason, final String detail ) {
        lock.lock();
        try {
            return new PublishStart( new PublishResponse( status, d.record.basePageVersion(), reason, false, null,
                detail ), null );
        } finally {
            lock.unlock();
        }
    }

    /**
     * After the caller claimed {@code publishing}: the pending-publish rule for an earlier unfinished publish, then the A1
     * backstop (an outside change the publisher has not seen is merged and answered 409 {@code publish-merged-retry}),
     * then the pre-save answers or the ticket with its stored marker.
     */
    PublishStart start( final Participant p, final String note, final String markupSyntax,
                        final boolean createChecked, final List< Runnable > completions ) {
        final Optional< PageState > latest;
        final ExternalMerge.Result merged;
        try {
            if( !settleMarker( completions ) ) {
                owe();
                return answered( 503, CollabSession.STORE );
            }
            latest = writer.latest( pageName() );
            merged = latest.isPresent() ? merge.mergeTo( latest.get(), completions ) : ExternalMerge.Result.NONE;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: reconciling before the publish failed; retried later: {}", d.id, e.getMessage() );
            owe();
            return answered( 503, CollabSession.STORE );
        }
        if( merged == ExternalMerge.Result.FAILED ) {
            owe();   // the page moved and the merge did not land: settle retries it
            return answered( 503, CollabSession.STORE );
        }
        settled();   // the marker is settled and the session is merged up to the page: nothing is owed any more
        if( merged == ExternalMerge.Result.CHANGED ) {
            return answered( 409, CollabSession.MERGED_RETRY );
        }
        lock.lock();
        try {
            return snapshotForSave( p, note, markupSyntax, createChecked, latest );
        } finally {
            lock.unlock();
        }
    }

    private String pageName() {
        lock.lock();
        try {
            return d.pageName();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Under the lock: 200 {@code unchanged} (E21), 400 {@code empty} (E1), 413 {@code oversize}; else V, S_V, M_V on base
     * B, the save request (a field still equal to the base keeps the base's original typed value) and the stored marker.
     * A frozen draft recreates its deleted page: B is 0 ("must not exist", A11) and it is published even unchanged,
     * but only when the publisher's {@code createPages} was checked ({@code createChecked}); a draft frozen after that
     * check was skipped answers 409 {@code frozen}, and the publisher's retry meets the check.
     */
    private PublishStart snapshotForSave( final Participant p, final String note, final String markupSyntax,
                                          final boolean createChecked, final Optional< PageState > latest ) {
        final boolean recreate = d.frozen();
        if( recreate && !createChecked ) {
            return new PublishStart( new PublishResponse( 409, d.record.basePageVersion(), CollabSession.FROZEN, false ),
                null );
        }
        final int b = recreate ? 0 : d.record.basePageVersion();
        final PublishResponse early = Gate.publishable( recreate || d.hasUnpublished(), d.doc.isEmpty(),
            Oversize.utf8( d.doc ) > d.oversize.maxPageBytes(), b );
        if( early != null ) {
            return new PublishStart( early, null );
        }
        final Map< String, Object > original = latest.filter( l -> l.version() == b
            && l.contentHash().equals( d.record.baseContentHash() ) ).map( PageState::meta ).orElse( Map.of() );
        final PublishStart.Ticket t = new PublishStart.Ticket( p.id, p.authorKey, p.displayName, d.head, d.doc, d.meta,
            d.metaSeq, b,
            new PublishRequest( d.pageName(), d.doc, MetaMerge.published( d.meta, original ), b, p.authorKey, note,
                markupSyntax ) );
        final SessionRecord marked = d.record.withPending( d.head, b, p.authorKey );
        try {
            // M_V made durable before the marker (A10: a snapshot at the publish point): the pending-publish rule reads
            // it back, so a recovered fold never takes meta edits made during the save for filter changes (review M5).
            // Labelled with the draft's base, not B: a recreate saves on B = 0, but local is relative to the base
            d.store.writeSnapshot( d.id, new Snapshot( d.head, d.metaSeq, d.record.basePageVersion(), d.doc, d.meta,
                d.local, d.contributors.snapshot() ) );
            d.lastSnapshotVersion = d.head;
            d.store.updateSession( marked );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): the publish snapshot or marker was not stored; not publishing: {}", d.id,
                d.pageName(), e.getMessage() );
            return new PublishStart( new PublishResponse( 503, b, CollabSession.STORE, false ), null );
        }
        d.record = marked;
        d.ticket = t;
        return new PublishStart( null, t );
    }

    /** The post-save half: N outside the lock, then the mapping and the base change under it. */
    PublishResponse finish( final PublishStart.Ticket t, final PublishOutcome saved, final List< Runnable > completions ) {
        final String savedBody = CollabText.toLf( saved.savedBody() );
        // N: both inputs are immutable, so the diff runs outside the session lock
        final ChangeSet n = TextDiff.changeSet( t.doc(), savedBody );
        lock.lock();
        try {
            return applyPublished( t, n, savedBody, saved, completions );
        } finally {
            lock.unlock();
        }
    }

    /**
     * Under the lock: W = the edits after V, the server update {@code N.map(W)}, {@code local = W.map(N)} on the new base,
     * the contributors credited at (V, M_V) written to the ledger ({@link #credit}; before the commit clears the marker,
     * so a failure leaves the publish to {@code settle}, whose retry writes them), the filters' meta changes folded as
     * server meta ops (stored before the base change), the base change committed with a snapshot of the live head and of
     * the contributors after the publish (live only once committed), a {@code published} event, then
     * {@code draft-state}.
     */
    private PublishResponse applyPublished( final PublishStart.Ticket t, final ChangeSet n, final String savedBody,
                                            final PublishOutcome saved, final List< Runnable > completions ) {
        final Draft.State before = d.state();
        final ChangeSet w = d.composedSince( t.version(), t.doc().length() );
        if( w == null ) {
            LOG.warn( "collab session {} ({}): the edits after the published version {} are not available; the publish is"
                + " finished later by the pending-publish rule", d.id, d.pageName(), t.version() );
            return unfinished( t );
        }
        final ChangeSet u = n.map( w );
        final ChangeSet localAfter = w.map( n, true );
        final String docAfter = u.apply( d.doc );
        final List< StoredUpdate > extra = u.empty() ? List.of()
            : List.of( new StoredUpdate( d.head + 1, StoredUpdate.SERVER, CollabSession.SERVER_CLIENT, null, u ) );
        final Map< String, Object > savedMeta = MetaJson.normalise( saved.savedMeta() );
        final Set< String > credited = d.contributors.creditedAt( t.version(), t.metaSeq() );
        if( !credit( t, saved.version(), savedMeta, credited ) ) {
            return unfinished( t );
        }
        if( !d.appendMetaOps( MetaMerge.ops( MetaMerge.fold( t.meta(), d.meta, savedMeta ), d.metaSeq ) ) ) {
            return unfinished( t );
        }
        final SessionRecord based = d.record.withBase( saved.version(), savedBody, saved.savedMeta(),
            CollabText.contentHash( savedBody ) ).withPending( null, null, null );
        // a publish on base 0 created the page: a frozen draft's recreate makes it editable again (E28)
        final SessionRecord updated = SessionLoader.draftMarkers( t.baseVersion() == 0
            ? based.withStatus( SessionRecord.ACTIVE ) : based, !localAfter.empty() || !d.meta.equals( savedMeta ),
            d.lastEditAt );
        if( !d.commit( extra, docAfter, localAfter, updated, d.contributors.afterPublish( t.version(), t.metaSeq() ) ) ) {
            return unfinished( t );
        }
        d.unfinished = null;
        d.feed.emit( "published", byline( t, "pageVersion", saved.version() ) );
        d.announce( before );
        answers.wakeAll( extra, completions );
        final String page = d.pageName();
        completions.add( () -> audit.published( d.id, page, t.authorKey(), saved.version(), t.version(), credited ) );
        return new PublishResponse( 200, saved.version(), null, false );
    }

    /**
     * Under the lock: replaces the ledger rows of page version {@code pageVersion} with the authors whose contributions
     * are inside the published snapshot (V, M_V; ruling R2), clearing the page's earlier rows when the publish created
     * it (base 0, ruling R4). Idempotent, so the pending-publish rule repeats it safely. A saved page without a
     * {@code canonical_id} cannot be attributed: skipped with a WARN. False (logged) when the store refused.
     */
    private boolean credit( final PublishStart.Ticket t, final int pageVersion, final Map< String, Object > savedMeta,
                            final Set< String > credited ) {
        final Object id = savedMeta.get( "canonical_id" );
        final String canonicalId = id == null ? "" : id.toString().trim();
        if( canonicalId.isEmpty() ) {
            LOG.warn( "collab session {} ({}): page version {} was saved without a canonical_id; its contributors are not"
                + " recorded", d.id, d.pageName(), pageVersion );
            return true;
        }
        try {
            d.store.recordContributors( canonicalId, pageVersion, credited, t.baseVersion() == 0 );
            return true;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): the contributors of page version {} were not recorded; the publish is"
                + " finished later: {}", d.id, d.pageName(), pageVersion, e.getMessage() );
            return false;
        }
    }

    private static Map< String, Object > byline( final PublishStart.Ticket t, final String key, final Object value ) {
        final Map< String, Object > f = new LinkedHashMap<>();
        if( t.displayName() != null ) {
            f.put( "by", t.displayName() );
        }
        f.put( key, value );
        return f;
    }

    /**
     * Under the lock: a publish whose completion failed: 503, the marker stays and is owed a retry (the pending-publish
     * rule), and the ticket's S_V is kept for it.
     */
    private PublishResponse unfinished( final PublishStart.Ticket t ) {
        d.unfinished = t;
        d.mergeOwed = true;
        return new PublishResponse( 503, d.record.basePageVersion(), CollabSession.STORE, false );
    }

    private PublishResponse unfinishedLocked( final PublishStart.Ticket t ) {
        lock.lock();
        try {
            return unfinished( t );
        } finally {
            lock.unlock();
        }
    }

    /**
     * A save that threw: version B+1 by the publisher with S_V's text means it persisted before failing (finished, A2);
     * a page-gone conflict (renamed or deleted away under the save) merges nothing and freezes nothing (ruling Q10: only
     * the delete listener freezes): it is owed a reconcile and answered 409 {@code publish-merged-retry}, and the rekey,
     * the freeze or {@code settle} resolves it before the retry; another B+1, or a version conflict, is an outside write,
     * merged, and answered 409 {@code publish-merged-retry} (E20, E27); otherwise the draft is unchanged and the others
     * are told ({@code publish-failed}, E19).
     */
    PublishResponse failed( final PublishStart.Ticket t, final Exception e, final List< Runnable > completions ) {
        LOG.warn( "collab session {} ({}): the save of the publish at version {} failed: {}", d.id, t.request().pageName(),
            t.version(), e.toString() );
        final String page = pageName();
        final Optional< PageState > next;
        try {
            next = writer.version( page, t.baseVersion() + 1 );
        } catch( final RuntimeException re ) {
            LOG.warn( "collab session {}: reading version {} after a failed save failed: {}", d.id, t.baseVersion() + 1,
                re.getMessage() );
            return unfinishedLocked( t );
        }
        if( next.isPresent() && ( claims( next.get(), t ) || savedUnread( e, next.get(), t ) ) ) {
            return finish( t, outcomeOf( next.get() ), completions );
        }
        if( !clearMarker() ) {
            return unfinishedLocked( t );
        }
        if( isPageGone( e ) && next.isEmpty() ) {
            // renamed or deleted away under the save: never frozen here (ruling Q10: only the delete listener freezes);
            // owed, so settle, the rekey or the freeze resolves it, and the publisher retries
            LOG.info( "collab session {} ({}): the page was gone at the save; owed a reconcile, publish to be retried",
                d.id, page );
            owe();
            return answered( 409, CollabSession.MERGED_RETRY ).response();
        }
        if( next.isPresent() || e instanceof VersionConflictException ) {
            try {
                reconcile( writer.latest( page ).orElse( null ), completions );
            } catch( final RuntimeException re ) {
                LOG.warn( "collab session {}: reading the page after a conflicting save failed; merged later: {}", d.id,
                    re.getMessage() );
                owe();
            }
            return answered( 409, CollabSession.MERGED_RETRY ).response();
        }
        return refused( t, e, completions );
    }

    /**
     * The save adapter's page-gone conflict (Phase 2 Task 4: {@code expectedVersion > 0} on a missing page):
     * {@link VersionConflictException#isPageGone()}.
     */
    static boolean isPageGone( final Exception e ) {
        return e instanceof VersionConflictException conflict && conflict.isPageGone();
    }

    /** 422 {@code writer-refused} (the exception rides along for the HTTP body) or 500 {@code writer-failed}. */
    private PublishResponse refused( final PublishStart.Ticket t, final Exception e, final List< Runnable > completions ) {
        final boolean runtime = e instanceof RuntimeException;
        final int status = runtime ? 500 : 422;
        final String reason = runtime ? "writer-failed" : "writer-refused";
        lock.lock();
        try {
            final Map< String, Object > f = byline( t, "status", status );
            f.put( "reason", reason );
            d.feed.emit( "publish-failed", f );
            answers.wakeAll( List.of(), completions );
            return new PublishResponse( status, d.record.basePageVersion(), reason, false, e );
        } finally {
            lock.unlock();
        }
    }

    /**
     * The pending-publish rule, then the merge up to {@code target} (null: the page is gone, only the rule runs). Records
     * whether a retry is owed; true when the base changed.
     */
    boolean reconcile( final PageState target, final List< Runnable > completions ) {
        final String before = baseKey();
        boolean ok;
        try {
            ok = settleMarker( completions )
                && ( target == null || merge.mergeTo( target, completions ) != ExternalMerge.Result.FAILED );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: reconciling with the page store failed; retried later: {}", d.id, e.getMessage() );
            ok = false;
        }
        lock.lock();
        try {
            d.mergeOwed = !ok;
        } finally {
            lock.unlock();
        }
        return !before.equals( baseKey() );
    }

    private void settled() {
        lock.lock();
        try {
            d.mergeOwed = false;
        } finally {
            lock.unlock();
        }
    }

    void owe() {
        lock.lock();
        try {
            d.mergeOwed = true;
        } finally {
            lock.unlock();
        }
    }

    /** The base page version and content hash, to tell whether a step changed the base. */
    String baseKey() {
        lock.lock();
        try {
            return d.baseKey();
        } finally {
            lock.unlock();
        }
    }

    /** True (and queued) when a publish is running: its end merges (A3). */
    boolean queuedBehindPublish() {
        lock.lock();
        try {
            return d.queueIfPublishing();
        } finally {
            lock.unlock();
        }
    }

    /** Clears the publishing flag; true when external merges were queued meanwhile (A3): the caller settles. */
    boolean endPublish() {
        lock.lock();
        try {
            d.publishing = false;
            d.ticket = null;
            final boolean drain = d.mergeQueued;
            d.mergeQueued = false;
            return drain;
        } finally {
            lock.unlock();
        }
    }

    /** A save's non-{@link Exception} throwable (an {@link Error}) as the runtime failure {@link #failed} handles. */
    static Exception asException( final Throwable cause ) {
        return cause instanceof Exception e ? e : new IllegalStateException( "the page save threw " + cause, cause );
    }

    /**
     * A2 / E39: with a pending-publish marker (V, B, author), version B+1 is claimed as that publish only when its author
     * is the marked one and its text hash is S_V's as the save stores it (A9: CRLF, trailing newline); the post-save half
     * then runs. Otherwise (another version, or none: the save never ran) the marker is cleared and a B+1 is merged as an
     * outside write by the caller. False when a store write failed (the marker stays).
     */
    private boolean settleMarker( final List< Runnable > completions ) {
        final Integer v;
        final int b;
        final String author;
        final String page;
        lock.lock();
        try {
            v = d.record.pendingPublishVersion();
            if( v == null ) {
                return true;
            }
            b = d.record.pendingPublishBaseVersion();
            author = d.record.pendingPublishAuthor();
            page = d.pageName();
        } finally {
            lock.unlock();
        }
        final Optional< PageState > next = writer.version( page, b + 1 );
        if( next.isPresent() ) {
            final PublishStart.Ticket t = recoveredTicket( v, b, author, page );
            if( t == null ) {
                LOG.warn( "collab session {} ({}): the published text at version {} cannot be rebuilt; page version {} is"
                    + " treated as an outside write", d.id, page, v, b + 1 );
            } else if( claims( next.get(), t ) ) {
                return finish( t, outcomeOf( next.get() ), completions ).status() == 200;
            }
        }
        return clearMarker();
    }

    /** The marked publish: the unfinished ticket in memory, or S_V rebuilt from the store; null when neither. */
    private PublishStart.Ticket recoveredTicket( final int v, final int b, final String author, final String page ) {
        lock.lock();
        try {
            if( d.unfinished != null && d.unfinished.version() == v && d.unfinished.baseVersion() == b ) {
                return d.unfinished;
            }
            final String sv = d.docAt( v );
            if( sv == null ) {
                return null;
            }
            final Draft.AtPublish at = d.atPublish( v );
            return new PublishStart.Ticket( null, author, identity.displayName( author ), v, sv, at.meta(), at.metaSeq(),
                b, new PublishRequest( page, sv, at.meta(), b, author, "" ) );
        } finally {
            lock.unlock();
        }
    }

    private static boolean claims( final PageState saved, final PublishStart.Ticket t ) {
        return t.authorKey().equals( saved.author() )
            && CollabText.contentHash( TextUtil.normalizePostData( t.doc() ) ).equals( saved.contentHash() );
    }

    /**
     * The save wrote {@code saved}'s version but its text could not be read back ({@link PublishReadBackException}): the
     * version is the publisher's own write even when its hash is not S_V's (a save filter rewrote the body), so it is
     * finished with the rewrite as N, as {@link #finish} maps a filter's rewrite. Merging it as an outside write instead
     * would apply the publisher's edits onto themselves and duplicate text. The author must still match.
     */
    private static boolean savedUnread( final Exception e, final PageState saved, final PublishStart.Ticket t ) {
        return e instanceof PublishReadBackException unread && unread.savedVersion() == saved.version()
            && t.authorKey().equals( saved.author() );
    }

    private static PublishOutcome outcomeOf( final PageState s ) {
        return new PublishOutcome( s.version(), s.body(), s.meta(), false );
    }

    private boolean clearMarker() {
        lock.lock();
        try {
            if( d.record.pendingPublishVersion() == null ) {
                return true;
            }
            final SessionRecord cleared = d.record.withPending( null, null, null );
            try {
                d.store.updateSession( cleared );
            } catch( final RuntimeException e ) {
                LOG.warn( "collab session {} ({}): clearing the pending-publish marker failed; retried later: {}", d.id,
                    d.pageName(), e.getMessage() );
                return false;
            }
            d.record = cleared;
            d.unfinished = null;
            return true;
        } finally {
            lock.unlock();
        }
    }
}
