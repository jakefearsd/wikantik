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
package com.wikantik.auth.identity;

import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.AccountSummary;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link PublicIdentity} backed by an in-memory snapshot of the account table.
 *
 * <p>One cheap query ({@link UserDatabase#accountSummaries()}: login, display name, stored uid) fills three hash maps, so every
 * lookup afterwards — including "this author is not an account" — is O(1) with no database access; that matters because
 * callers resolve one value per result row. The snapshot is trusted for {@link #TTL} on the injected {@link Clock}, so a rename
 * made on another node or directly in the database shows within a minute; {@link #invalidate} and the profile events
 * registered by {@code AuthSubsystemFactory} drop it at once on this node.</p>
 *
 * <p>If a reload fails the previous snapshot keeps being served (retry in {@link #RETRY_AFTER_FAILURE}); with no snapshot at
 * all the failure is logged at WARN and answered with the neutral fallback ({@link #UNKNOWN} for a non-email value,
 * {@link #UNKNOWN_CONTRIBUTOR} for an email-shaped one). With a {@code null} database no account is known: values pass
 * through, email-shaped ones are still masked.</p>
 */
public final class DefaultPublicIdentity implements PublicIdentity, WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( DefaultPublicIdentity.class );

    /** How long a loaded snapshot is trusted. */
    static final Duration TTL = Duration.ofSeconds( 60 );

    /** After a failed load: how long before the database is asked again. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds( 5 );

    private final UserDatabase db;
    private final Clock clock;
    private volatile AccountSnapshot snapshot;
    private volatile Instant retryAt;

    /**
     * Bumped by every {@link #invalidate}. A snapshot is only fresh while its generation is the current one, so an
     * invalidation also reaches a load that was already in flight (its result may predate the write) — and the superseded
     * snapshot stays available as the fallback should the reload fail.
     */
    private final AtomicLong generation = new AtomicLong();

    /**
     * @param db    the user database; {@code null} means "no accounts are known"
     * @param clock the clock the 60 s snapshot lifetime is measured on
     */
    public DefaultPublicIdentity( final UserDatabase db, final Clock clock ) {
        this.db = db;
        this.clock = Objects.requireNonNull( clock, "clock" );
    }

    @Override
    public String displayName( final String storedPrincipal ) {
        if ( storedPrincipal == null || storedPrincipal.isBlank() ) {
            return UNKNOWN;
        }
        final String stored = storedPrincipal.trim();
        final boolean emailShaped = stored.indexOf( '@' ) >= 0;
        final AccountSnapshot current = current();
        if ( current == null ) {
            return emailShaped ? UNKNOWN_CONTRIBUTOR : UNKNOWN;
        }
        final AccountSummary account = current.byLogin().get( stored );
        if ( account == null ) {
            return emailShaped ? UNKNOWN_CONTRIBUTOR : stored;
        }
        final String name = account.fullName() == null ? "" : account.fullName().trim();
        return name.isEmpty() || name.indexOf( '@' ) >= 0 ? UNKNOWN_CONTRIBUTOR : name;
    }

    @Override
    public Optional< String > userIdForLogin( final String login ) {
        if ( login == null || login.isBlank() ) {
            return Optional.empty();
        }
        final AccountSnapshot current = current();
        final AccountSummary account = current == null ? null : current.byLogin().get( login.trim() );
        return account == null ? Optional.empty() : Optional.ofNullable( account.uid() );
    }

    @Override
    public Optional< String > loginForUserId( final String uid ) {
        if ( uid == null || uid.isBlank() ) {
            return Optional.empty();
        }
        final AccountSnapshot current = current();
        return current == null ? Optional.empty() : Optional.ofNullable( current.loginByUid().get( uid.trim() ) );
    }

    @Override
    public Optional< String > loginForDisplayName( final String displayName ) {
        if ( displayName == null || displayName.isBlank() ) {
            return Optional.empty();
        }
        final AccountSnapshot current = current();
        return current == null ? Optional.empty()
                : Optional.ofNullable( current.loginByName().get( displayName.trim().toLowerCase( Locale.ROOT ) ) );
    }

    @Override
    public void invalidate( final String login ) {
        // Cheap to refill (one query), and a rename changes name->login and uid->login answers for other keys too.
        generation.incrementAndGet();
        retryAt = null;
    }

    /** Invalidates on {@code PROFILE_SAVE} and {@code PROFILE_NAME_CHANGED} (the legacy {@code UserManager} path). */
    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiSecurityEvent securityEvent
                && ( securityEvent.getType() == WikiSecurityEvent.PROFILE_SAVE
                     || securityEvent.getType() == WikiSecurityEvent.PROFILE_NAME_CHANGED ) ) {
            invalidate( null );
        }
    }

    // ------------------------------------------------------------------ snapshot

    private boolean fresh( final AccountSnapshot held, final Instant now ) {
        return held != null && now.isBefore( held.expires() ) && held.generation() == generation.get();
    }

    /** The snapshot to answer from, reloading it when stale; the previous one if the reload failed, {@code null} if none was ever loaded. */
    private AccountSnapshot current() {
        final Instant now = clock.instant();
        final AccountSnapshot held = snapshot;
        if ( fresh( held, now ) ) {
            return held;
        }
        final Instant notBefore = retryAt;
        if ( notBefore != null && now.isBefore( notBefore ) ) {
            return held;   // a recent load failed: keep serving the stale snapshot (or none) without asking again
        }
        return reload( now );
    }

    private synchronized AccountSnapshot reload( final Instant now ) {
        final AccountSnapshot held = snapshot;
        if ( fresh( held, now ) ) {
            return held;   // another thread reloaded while we waited
        }
        final long loading = generation.get();
        try {
            final AccountSnapshot loaded = AccountSnapshot.build( now.plus( TTL ), loading,
                    db == null ? List.of() : db.accountSummaries() );
            snapshot = loaded;
            retryAt = null;
            warnWhenAmbiguousNamesChanged( held, loaded );
            return loaded;
        } catch ( final WikiSecurityException | RuntimeException e ) {
            retryAt = now.plus( RETRY_AFTER_FAILURE );
            LOG.warn( "Could not load the account snapshot; {}: {}",
                    held == null ? "showing neutral labels" : "serving the previous snapshot", e.getMessage(), e );
            return held;
        }
    }

    /** Logged when the set changes, not on every 60 s rebuild: a legacy duplicate would otherwise warn once a minute forever. */
    private static void warnWhenAmbiguousNamesChanged( final AccountSnapshot before, final AccountSnapshot after ) {
        final Set< String > previous = before == null ? Set.of() : before.ambiguousNames();
        if ( !after.ambiguousNames().isEmpty() && !after.ambiguousNames().equals( previous ) ) {
            LOG.warn( "Display names held by more than one account will not resolve to a login: {}", after.ambiguousNames() );
        }
    }
}
