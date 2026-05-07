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
package com.wikantik.render.subsystem.spam;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.RedirectException;

/**
 * Rate-limit + IP-ban bucket of the decomposed SpamFilter.
 *
 * <p>Owns the in-memory temporary ban list and the per-IP page-changes-per-minute
 * window. Extracted from {@code SpamFilter} in Phase 6 Checkpoint 3 of the
 * wikantik-main subsystem decomposition.</p>
 */
public interface SpamRateLimiter {

    /**
     * Removes expired entries from the temporary ban list.
     * Must be called at the start of every {@code preSave} cycle.
     */
    void cleanBanList();

    /**
     * Checks whether the remote IP in the request is on the temporary ban list.
     *
     * @param context page context
     * @param change  the current change (used for logging)
     * @throws RedirectException if the IP is temporarily banned
     */
    void checkBanList( Context context, SpamChange change ) throws RedirectException;

    /**
     * Tracks the current modification and enforces the per-minute page-change limit
     * and the similar-modifications limit. Adds the IP to the temporary ban list when
     * a limit is exceeded.
     *
     * @param context page context
     * @param change  the current change
     * @throws RedirectException if a limit is exceeded
     */
    void checkSinglePageChange( Context context, SpamChange change ) throws RedirectException;

    /**
     * Records a modification in the recent-modifications window AFTER all checks pass.
     * Must be called at the end of {@link #checkSinglePageChange} internally.
     * (Exposed so {@code DefaultSpamRateLimiter} can call it at the right point.)
     */
    void recordModification( String addr, SpamChange change );
}
