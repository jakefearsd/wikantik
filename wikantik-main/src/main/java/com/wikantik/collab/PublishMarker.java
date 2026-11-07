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

import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.text.CollabText;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The pending-publish marker of {@link PublishFlow} (A2, E39, as amended by ruling D): recording on it the page version
 * a publish's save wrote and the canonical id it credits under, so every later claim matches (saved version, author)
 * rather than S_V's content hash; rebuilding the marked publish's ticket; and clearing it, with the ledger rows of a
 * publish that went unclaimed. Methods documented "under the lock" expect the session lock held; the others take it.
 */
final class PublishMarker {

    private static final Logger LOG = LogManager.getLogger( PublishMarker.class );

    private final Draft d;
    private final ReentrantLock lock;
    private final CollabIdentity identity;

    PublishMarker( final Draft d, final ReentrantLock lock, final CollabIdentity identity ) {
        this.d = d;
        this.lock = lock;
        this.identity = identity;
    }

    /**
     * Under the lock: records on the pending-publish marker the page version its save wrote, the canonical id it was
     * saved under and the content hash of the text it stored (rulings D and D2b), so the retry and the reload's
     * pending-publish rule claim the publish by (saved version, author, saved hash) ({@link #claimsSaved}) and find its
     * early ledger rows when it goes unclaimed. Always written, also when memory already holds it, so the ledger rows
     * {@link PublishFlow} credits next never exist without a stored marker naming them. The session keeps it in memory
     * even when the store refuses (false, logged): its own retry still claims by it, and a crash before the next
     * session write falls back to the content-hash rule.
     */
    boolean saved( final int savedVersion, final String canonicalId, final String savedHash ) {
        if( d.record.pendingPublishVersion() == null ) {
            return true;
        }
        final SessionRecord marked = d.record.withPublishSaved( savedVersion, canonicalId, savedHash );
        d.record = marked;
        try {
            d.store.updateSession( marked );
            return true;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): storing the version {} the publish saved on its marker failed; the publish is"
                + " finished later: {}", d.id, d.pageName(), savedVersion, e.getMessage() );
            return false;
        }
    }

    /**
     * {@link #saved} for a failed save that only reported its version ({@link PublishReadBackException}): its text was
     * never read, so neither the canonical id nor the saved hash is known, and a claim of it falls back to (saved
     * version, author). Takes the lock.
     */
    boolean savedLocked( final int savedVersion ) {
        lock.lock();
        try {
            return saved( savedVersion, null, null );
        } finally {
            lock.unlock();
        }
    }

    /** The marked publish: the unfinished ticket in memory, or S_V rebuilt from the store; null when neither. */
    PublishStart.Ticket recovered( final int v, final int b, final String author, final String page ) {
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

    /**
     * Ruling D2b: a marker's saved version is claimed only when the version now in the page store holds the text the
     * save stored ({@code savedHash}), so a page deleted and recreated up to that version number, even by the same
     * author, is not mistaken for the publish. When only the version was reported ({@code savedHash} null: a read-back
     * failure, {@link #savedLocked}) the claim falls back to version and author, which the caller has checked.
     */
    static boolean claimsSaved( final String savedHash, final PageState now ) {
        return savedHash == null || savedHash.equals( now.contentHash() );
    }

    /** A2's content-hash rule, for a marker without a saved version: {@code saved} is by the author with S_V's text. */
    static boolean claims( final PageState saved, final PublishStart.Ticket t ) {
        return t.authorKey().equals( saved.author() )
            && CollabText.contentHash( TextUtil.normalizePostData( t.doc() ) ).equals( saved.contentHash() );
    }

    /**
     * The page version a failed save reported writing ({@link PublishReadBackException#savedVersion}), when it is a new
     * version above the ticket's base; 0 otherwise (nothing reported, or a save that stored nothing new).
     */
    static int reportedVersion( final Exception e, final PublishStart.Ticket t ) {
        return e instanceof PublishReadBackException unread && unread.savedVersion() > t.baseVersion()
            ? unread.savedVersion() : 0;
    }

    /**
     * An unclaimed publish: the ledger rows at the version its save wrote (the marker names both that version and the
     * canonical id the publish credits under) are replaced by none, whether this publish's credit wrote them or they
     * were left by an earlier page at that version number, so a publish that did not complete credits nobody and its
     * contributors, still pending, are credited once by the next publish (ruling D2a). Only that version's rows: a
     * recreate's (base 0) earlier rows were already cleared by its credit if that ran (R4), and are kept if it never did
     * (review M2). Then the marker is cleared. False (logged) when a store write failed: the marker stays and the retry
     * repeats both steps.
     */
    boolean dropUnclaimed() {
        lock.lock();
        try {
            final Integer savedVersion = d.record.pendingPublishSavedVersion();
            final String canonicalId = d.record.pendingPublishCanonicalId();
            if( savedVersion != null && canonicalId != null && savedVersion > 0 ) {
                try {
                    d.store.recordContributors( canonicalId, savedVersion, Set.of(), false );
                } catch( final RuntimeException e ) {
                    LOG.warn( "collab session {} ({}): removing the ledger rows of the unclaimed publish of page version {}"
                        + " failed; retried later: {}", d.id, d.pageName(), savedVersion, e.getMessage() );
                    return false;
                }
                LOG.info( "collab session {} ({}): the publish that saved page version {} was not claimed (the page moved"
                    + " on); its contributors stay pending", d.id, d.pageName(), savedVersion );
            }
            return clear();
        } finally {
            lock.unlock();
        }
    }

    /** Clears the marker (and the unfinished ticket); false (logged) when the store refused, the marker staying. */
    boolean clear() {
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
