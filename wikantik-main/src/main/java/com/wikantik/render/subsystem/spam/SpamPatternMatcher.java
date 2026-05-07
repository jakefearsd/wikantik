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
 * Word/IP regex blacklist + URL-count + page-name-length + UTF-8 trap bucket
 * of the decomposed SpamFilter.
 *
 * <p>Owns the regex collections and their wiki-page-driven reload path.
 * Extracted from {@code SpamFilter} in Phase 6 Checkpoint 3 of the
 * wikantik-main subsystem decomposition.</p>
 */
public interface SpamPatternMatcher {

    /**
     * Reloads the spam word and IP blacklists from the configured wiki pages and
     * attachment, if any of them have been modified since the last reload.
     *
     * @param context page context used to look up the attachment
     */
    void refreshBlacklists( Context context );

    /**
     * Checks the content change and remote IP against the loaded spam-word patterns.
     *
     * @param context page context
     * @param change  the current change
     * @throws RedirectException if a pattern matches
     */
    void checkPatternList( Context context, SpamChange change ) throws RedirectException;

    /**
     * Checks a plain string against the loaded spam-word patterns.
     * Used by {@code isValidUserProfile}.
     *
     * @param context page context
     * @param text    the text to check
     * @throws RedirectException if a pattern matches
     */
    void checkPatternList( Context context, String text ) throws RedirectException;

    /**
     * Checks the remote IP against the loaded IP-ban patterns.
     *
     * @param context page context
     * @throws RedirectException if a pattern matches
     */
    void checkIPList( Context context ) throws RedirectException;

    /**
     * Checks whether the page name exceeds the configured maximum length.
     *
     * @param context page context
     * @throws RedirectException if the page name is too long
     */
    void checkPageName( Context context ) throws RedirectException;

    /**
     * Computes the textual difference introduced by {@code newText} relative to the
     * current page content, including any change note and author line.
     *
     * @param context  page context
     * @param newText  the new content being saved
     * @return a {@link SpamChange} describing what was added/removed
     */
    SpamChange getChange( Context context, String newText );

    /**
     * Returns the name of the page that contains the forbidden-words list.
     */
    String getForbiddenWordsPage();

    /**
     * Returns the configured error-redirect page name.
     */
    String getErrorPage();
}
