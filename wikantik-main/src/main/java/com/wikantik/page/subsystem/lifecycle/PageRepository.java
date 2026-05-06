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
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.pages.PageSorter;
import com.wikantik.api.providers.PageProvider;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Storage-access façade over {@link PageProvider}.
 *
 * <p>Owns every read / write / exists / version / delete / sort operation
 * that touches the underlying page store. Extracted from
 * {@code DefaultPageManager} in Phase 5 Checkpoint 3 of the wikantik-main
 * subsystem decomposition.</p>
 */
public interface PageRepository {

    /** Returns the underlying {@link PageProvider}. */
    PageProvider getProvider();

    /** @see com.wikantik.api.managers.PageManager#getAllPages() */
    Collection<Page> getAllPages() throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#getPageText(String, int) */
    String getPageText( String pageName, int version ) throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#getPureText(String, int) */
    String getPureText( String pageName, int version );

    /** @see com.wikantik.api.managers.PageManager#getText(String, int) */
    String getText( String pageName, int version );

    /** @see com.wikantik.api.managers.PageManager#putPageText(Page, String) */
    void putPageText( Page page, String content ) throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#getPage(String) */
    Page getPage( String pagereq );

    /** @see com.wikantik.api.managers.PageManager#getPage(String, int) */
    Page getPage( String pagereq, int version );

    /** @see com.wikantik.api.managers.PageManager#getPageInfo(String, int) */
    Page getPageInfo( String pageName, int version ) throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#getVersionHistory(String) */
    <T extends Page> List<T> getVersionHistory( String pageName );

    /** @see com.wikantik.api.managers.PageManager#getCurrentProvider() */
    String getCurrentProvider();

    /** @see com.wikantik.api.managers.PageManager#getProviderDescription() */
    String getProviderDescription();

    /** @see com.wikantik.api.managers.PageManager#getTotalPageCount() */
    int getTotalPageCount();

    /** @see com.wikantik.api.managers.PageManager#getRecentChanges() */
    Set<Page> getRecentChanges();

    /** @see com.wikantik.api.managers.PageManager#getRecentChanges(Date) */
    Set<Page> getRecentChanges( Date since );

    /** @see com.wikantik.api.managers.PageManager#pageExists(String) */
    boolean pageExists( String pageName ) throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#pageExists(String, int) */
    boolean pageExists( String pageName, int version ) throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#wikiPageExists(String) */
    boolean wikiPageExists( String page );

    /** @see com.wikantik.api.managers.PageManager#wikiPageExists(String, int) */
    boolean wikiPageExists( String page, int version ) throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#deleteVersion(Page) */
    void deleteVersion( Page page ) throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#deletePage(String) */
    void deletePage( String pageName ) throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#deletePage(Page) */
    void deletePage( Page page ) throws ProviderException;

    /** @see com.wikantik.api.managers.PageManager#getPageSorter() */
    PageSorter getPageSorter();
}
