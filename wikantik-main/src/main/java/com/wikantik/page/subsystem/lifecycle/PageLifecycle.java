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

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.pages.SaveGuard;
import com.wikantik.api.pages.SaveOutcome;

/**
 * Orchestrates the page-save lifecycle: pre-save filtering, persistence,
 * post-save filtering, and search re-indexing.
 *
 * <p>Extracted from {@code DefaultPageManager} in Phase 5 Checkpoint 3 of the
 * wikantik-main subsystem decomposition.</p>
 */
public interface PageLifecycle {

    /** @see com.wikantik.api.managers.PageManager#saveText(Context, String) */
    void saveText( Context context, String text ) throws WikiException;

    /**
     * Guarded save: the guard is checked atomically with the write; reports the version written. When the page was
     * written but the post-save handling then failed, throws
     * {@link com.wikantik.api.pages.SavedButPostSaveFailedException} carrying the outcome.
     */
    SaveOutcome saveText( Context context, String text, SaveGuard guard ) throws WikiException;

    /**
     * Writes {@code text} as a new version of {@code page} with no filters and no save events, under the page's save
     * lock, so it cannot land between a guarded save's check and its write.
     *
     * @see com.wikantik.api.managers.PageManager#putPageText(Page, String)
     */
    void putPageText( Page page, String text ) throws ProviderException;

    /**
     * Runs {@code action} holding the save lock of {@code pageName}: a read, rewrite and {@link #putPageText} inside it
     * cannot lose a concurrent guarded save of that page. The lock is reentrant, so the action may write the page. The
     * action must not wait for another thread's save, and should not fire events a listener may answer with a save of
     * another page (fire them after this returns).
     */
    < T > T underPageLock( String pageName, PageLockedAction< T > action ) throws WikiException;

    /**
     * {@link #underPageLock} on {@code lifecycle}, or {@code action} run directly when there is none (the page manager is
     * not the default one, e.g. a test double, so no save takes the lock either).
     */
    static < T > T underPageLock( final PageLifecycle lifecycle, final String pageName, final PageLockedAction< T > action )
            throws WikiException {
        return lifecycle == null ? action.run() : lifecycle.underPageLock( pageName, action );
    }

    /** Work done under a page's save lock. */
    @FunctionalInterface
    interface PageLockedAction< T > {
        T run() throws WikiException;
    }
}
