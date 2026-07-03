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
package com.wikantik.auth.permissions;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wikantik.api.core.Page;


/**
 *  Provides a factory for Permission objects.  Since the Permissions are immutable,
 *  and creating them takes a bit of time, caching them makes sense.
 *  <p>
 *  This class stores the permissions in a static HashMap.
 *  @since 2.5.54
 */
public final class PermissionFactory
{
    /**
     *  Prevent instantiation.
     */
    private PermissionFactory() {}

    /**
     * Bounded, lock-free-on-hit cache of immutable PagePermission objects.
     * Replaces the legacy synchronized(WeakHashMap) keyed by XOR'd hashcodes,
     * which (a) took a process-global monitor on every permission check and
     * (b) could return the wrong permission on a hashcode collision.
     */
    private static final Cache< String, PagePermission > CACHE =
        Caffeine.newBuilder().maximumSize( 50_000 ).build();

    /**
     *  Get a permission object for a WikiPage and a set of actions.
     *
     *  @param page The page object.
     *  @param actions A list of actions.
     *  @return A PagePermission object, presenting this page+actions combination.
     */
    public static PagePermission getPagePermission( final Page page, final String actions )
    {
        return getPagePermission( page.getWiki(), page.getName(), actions );
    }

    /**
     *  Get a permission object for a WikiPage and a set of actions.
     *
     *  @param page The name of the page.
     *  @param actions A list of actions.
     *  @return A PagePermission object, presenting this page+actions combination.
     */
    public static PagePermission getPagePermission( final String page, final String actions )
    {
        return getPagePermission( "", page, actions );
    }

    /**
     *  Get a page permission based on a wiki, page, and actions.
     *
     *  @param wiki The name of the wiki. Can be an empty string, but must not be null.
     *  @param page The page name
     *  @param actions A list of actions.
     *  @return A PagePermission object.
     */
    private static PagePermission getPagePermission( final String wiki, String page, final String actions )
    {
        final String key = wiki + ' ' + page + ' ' + actions;
        return CACHE.get( key, k -> {
            final String qualified = wiki.isEmpty() ? page : wiki + ":" + page;
            return new PagePermission( qualified, actions );
        } );
    }

}
