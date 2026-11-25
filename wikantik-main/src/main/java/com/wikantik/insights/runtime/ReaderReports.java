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
package com.wikantik.insights.runtime;

import com.wikantik.insights.readers.PageDirectory;
import com.wikantik.insights.readers.ReaderReport;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Optional;

/** Builds the {@link ReaderReport} used by the admin REST endpoint and the admin MCP tool. */
public final class ReaderReports {

    private ReaderReports() {
    }

    /** A report over {@code ds}; slugs and clusters come from the canonical-id table. */
    public static ReaderReport create( final DataSource ds, final String frontPageName ) {
        final PageCanonicalIdsDao dao = new PageCanonicalIdsDao( ds );
        final PageDirectory directory = id -> dao.findByCanonicalId( id )
                .map( row -> new PageDirectory.Entry( row.currentSlug(), row.cluster() ) );
        // The path resolver memoises per instance, so a long-lived report (the MCP tool keeps one) can
        // serve a stale mapping after a rename until the process restarts; the REST endpoint builds a
        // new report per request.
        return new ReaderReport( new Jdbc( ds ), new WikiPathResolver( dao, frontPageName ), directory,
                Clock.systemUTC() );
    }
}
