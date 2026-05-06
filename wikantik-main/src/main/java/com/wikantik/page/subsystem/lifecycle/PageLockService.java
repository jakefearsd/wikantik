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

import com.wikantik.api.core.Page;
import com.wikantik.api.pages.PageLock;

import java.util.List;

/**
 * Manages page edit locks and their background expiry reaper.
 *
 * <p>Extracted from {@code DefaultPageManager} in Phase 5 Checkpoint 3 of the
 * wikantik-main subsystem decomposition.</p>
 */
public interface PageLockService {

    /** @see com.wikantik.api.managers.PageManager#lockPage(Page, String) */
    PageLock lockPage( Page page, String user );

    /** @see com.wikantik.api.managers.PageManager#unlockPage(PageLock) */
    void unlockPage( PageLock lock );

    /** @see com.wikantik.api.managers.PageManager#getCurrentLock(Page) */
    PageLock getCurrentLock( Page page );

    /** @see com.wikantik.api.managers.PageManager#getActiveLocks() */
    List<PageLock> getActiveLocks();

    /**
     * Stops the background lock-reaper thread and clears all held locks.
     * Called during engine shutdown.
     */
    void shutdown();
}
