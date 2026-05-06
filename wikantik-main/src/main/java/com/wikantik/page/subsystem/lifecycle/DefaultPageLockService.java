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
package com.wikantik.page.subsystem.lifecycle;

import com.wikantik.WikiBackgroundThread;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.pages.PageLock;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link PageLockService}.
 *
 * <p>All logic is moved verbatim from {@code DefaultPageManager} in
 * Phase 5 Checkpoint 3 of the wikantik-main subsystem decomposition.</p>
 */
public class DefaultPageLockService implements PageLockService {

    private static final Logger LOG = LogManager.getLogger( DefaultPageLockService.class );

    private final Engine engine;
    private final int    expiryTime;
    /** The object on behalf of which {@link WikiEventManager} events are fired. */
    private final Object eventSource;

    protected final ConcurrentHashMap<String, PageLock> pageLocks = new ConcurrentHashMap<>();
    private LockReaper reaper;

    /**
     * @param engine      Engine instance (used to start the background reaper thread)
     * @param expiryTime  Lock expiry time in minutes
     * @param eventSource Object used as the event source for {@link WikiEventManager} registration
     */
    public DefaultPageLockService( final Engine engine, final int expiryTime, final Object eventSource ) {
        this.engine      = engine;
        this.expiryTime  = expiryTime;
        this.eventSource = eventSource;
    }

    // --- Event helper ---

    private void fireEvent( final int type, final String pagename ) {
        if ( WikiEventManager.isListening( eventSource ) ) {
            com.wikantik.core.subsystem.CoreSubsystemBridge.fromLegacyEngine( engine )
                .eventBus().fireEvent( eventSource, new WikiPageEvent( engine, type, pagename ) );
        }
    }

    // -------------------------------------------------------------------------
    // PageLockService implementation
    // -------------------------------------------------------------------------

    @Override
    public PageLock lockPage( final Page page, final String user ) {
        if ( reaper == null ) {
            //  Start the lock reaper lazily.  We don't want to start it in the constructor, because starting threads in constructors
            //  is a bad idea when it comes to inheritance.  Besides, laziness is a virtue.
            reaper = new LockReaper( engine );
            reaper.start();
        }

        fireEvent( WikiPageEvent.PAGE_LOCK, page.getName() );
        final Date lockTime = new Date();
        final PageLock newLock = new PageLock( page, user, lockTime, new Date( lockTime.getTime() + expiryTime * 60 * 1000L ) );
        final PageLock existing = pageLocks.putIfAbsent( page.getName(), newLock );

        if ( existing == null ) {
            LOG.debug( "Locked page {} for {}", page.getName(), user );
            return newLock;
        } else {
            LOG.debug( "Page {} already locked by {}", page.getName(), existing.getLocker() );
            return null;
        }
    }

    @Override
    public void unlockPage( final PageLock lock ) {
        if ( lock == null ) {
            return;
        }

        pageLocks.remove( lock.getPage() );
        LOG.debug( "Unlocked page {}", lock.getPage() );

        fireEvent( WikiPageEvent.PAGE_UNLOCK, lock.getPage() );
    }

    @Override
    public PageLock getCurrentLock( final Page page ) {
        return pageLocks.get( page.getName() );
    }

    @Override
    public List<PageLock> getActiveLocks() {
        return new ArrayList<>( pageLocks.values() );
    }

    @Override
    public void shutdown() {
        if ( reaper != null ) {
            reaper.shutdown();
            reaper = null;
        }
        pageLocks.clear();
    }

    // -------------------------------------------------------------------------
    // LockReaper background thread
    // -------------------------------------------------------------------------

    /**
     * This is a simple reaper thread that runs roughly every minute
     * or so (it's not really that important, as long as it runs),
     * and removes all locks that have expired.
     */
    private class LockReaper extends WikiBackgroundThread {

        public LockReaper( final Engine newEngine ) {
            super( engine, 60 );
            setName( "JSPWiki Lock Reaper" );
        }

        @Override
        public void backgroundTask() {
            final Collection<PageLock> entries = pageLocks.values();
            for ( final Iterator<PageLock> i = entries.iterator(); i.hasNext(); ) {
                final PageLock p = i.next();

                if ( p.isExpired() ) {
                    i.remove();

                    LOG.debug( "Reaped lock: {} by {}, acquired {}, and expired {}",
                               p.getPage(), p.getLocker(), p.getAcquisitionTime(), p.getExpiryTime() );
                }
            }
        }
    }
}
