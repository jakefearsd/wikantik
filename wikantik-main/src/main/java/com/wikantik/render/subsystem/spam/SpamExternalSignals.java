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
 * Akismet integration + bot-trap hidden-field check + UTF-8 trap bucket of the
 * decomposed SpamFilter.
 *
 * <p>Owns the Akismet client (lazy-initialised on first use) and the two
 * request-field traps. Extracted from {@code SpamFilter} in Phase 6 Checkpoint 3
 * of the wikantik-main subsystem decomposition.</p>
 */
public interface SpamExternalSignals {

    /**
     * Checks whether the hidden bot-trap field in the request is non-empty.
     *
     * @param context page context
     * @param change  current change (used for logging)
     * @throws RedirectException if the bot trap fires
     */
    void checkBotTrap( Context context, SpamChange change ) throws RedirectException;

    /**
     * Checks whether the UTF-8 sentinel field in the request has been mangled.
     *
     * @param context page context
     * @param change  current change (used for logging)
     * @throws RedirectException if the UTF-8 trap fires
     */
    void checkUTF8( Context context, SpamChange change ) throws RedirectException;

    /**
     * Submits the change to Akismet for spam classification.
     * No-op when no API key is configured.
     *
     * @param context page context
     * @param change  current change
     * @throws RedirectException if Akismet classifies the change as spam
     */
    void checkAkismet( Context context, SpamChange change ) throws RedirectException;
}
