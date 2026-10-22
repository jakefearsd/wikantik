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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.Verification;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code page_verification} rows held in memory: loaded in one query at each structural rebuild and
 * kept current by the index's own writes, so per-page verification lookups (agent projections, admin
 * audits, bundle confidence) cost no query. Before the first successful load, lookups fall back to the
 * DAO. A row written behind the index's back (raw SQL) is seen at the next rebuild.
 */
final class VerificationStore {

    private final PageVerificationDao dao;
    /** {@code null} until the first successful {@link #reload()}: lookups then go to the DAO. */
    private volatile Map< String, Verification > rows;

    /** @param dao the table's DAO; {@code null} disables verification entirely */
    VerificationStore( final PageVerificationDao dao ) {
        this.dao = dao;
    }

    boolean wired() {
        return dao != null;
    }

    Optional< Verification > get( final String canonicalId ) {
        if ( dao == null || canonicalId == null || canonicalId.isBlank() ) {
            return Optional.empty();
        }
        final Map< String, Verification > cached = rows;
        return cached != null ? Optional.ofNullable( cached.get( canonicalId ) ) : dao.findByCanonicalId( canonicalId );
    }

    /** Replaces the in-memory rows with one read of the table; keeps the previous view when the read fails. */
    void reload() {
        if ( dao == null ) {
            return;
        }
        final Map< String, Verification > all = dao.findAll();
        if ( all != null ) {
            rows = new ConcurrentHashMap<>( all );
        }
    }

    /** Persists the row, then mirrors it in memory. */
    void save( final String canonicalId, final Verification verification ) {
        dao.upsert( canonicalId, verification );
        final Map< String, Verification > cached = rows;
        if ( cached != null ) {
            cached.put( canonicalId, verification );
        }
    }

    /** Forgets a deleted page's row (the DAO row goes with its canonical id). */
    void remove( final String canonicalId ) {
        final Map< String, Verification > cached = rows;
        if ( cached != null ) {
            cached.remove( canonicalId );
        }
    }
}
