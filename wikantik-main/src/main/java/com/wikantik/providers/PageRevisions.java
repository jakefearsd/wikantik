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
package com.wikantik.providers;

import com.wikantik.api.core.Page;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Page-revision comparisons the page cache uses to refresh and to answer change feeds without the store. */
final class PageRevisions {

    private PageRevisions() {}

    /** True when the two Page objects describe the same stored revision (version, modification time, size, author). */
    static boolean same( final Page cached, final Page fresh ) {
        return cached.getVersion() == fresh.getVersion()
                && cached.getSize() == fresh.getSize()
                && Objects.equals( cached.getLastModified(), fresh.getLastModified() )
                && Objects.equals( cached.getAuthor(), fresh.getAuthor() );
    }

    /**
     * The pages modified at or after {@code date}; all of them when {@code date} is null or the epoch. A page
     * with no modification time is included, as the file provider includes it.
     */
    static Collection< Page > changedSince( final Collection< Page > all, final Date date ) {
        final long sinceMillis = ( date == null ) ? 0L : date.getTime();
        if( sinceMillis <= 0L ) {
            return all;
        }
        final List< Page > changed = new ArrayList<>();
        for( final Page page : all ) {
            final Date modified = page.getLastModified();
            if( modified == null || modified.getTime() >= sinceMillis ) {
                changed.add( page );
            }
        }
        return changed;
    }
}
