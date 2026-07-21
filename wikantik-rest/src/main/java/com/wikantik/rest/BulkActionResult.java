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
package com.wikantik.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GoF Builder for the standard bulk-action result envelope shared by the admin
 * bulk endpoints (users, API keys, KG proposals). Three resources previously
 * re-implemented the same shape by hand:
 *
 * <pre>{ "succeeded": [ids…], "failed": [{"id":…, "error":…}…],
 *   "status": "completed", "message": "N of M &lt;noun phrase&gt;" }</pre>
 *
 * Field ORDER is part of the wire contract of these endpoints — the envelope is
 * built on a {@link LinkedHashMap}; callers may append extra resource-specific
 * fields (e.g. {@code warnings_by_proposal}) to the returned map, which keeps
 * them after {@code message} exactly as before.
 */
public final class BulkActionResult {

    private final List< String > succeeded = new ArrayList<>();
    private final List< Map< String, Object > > failed = new ArrayList<>();

    public void succeed( final String id ) {
        succeeded.add( id );
    }

    public void fail( final String id, final String error ) {
        final Map< String, Object > f = new LinkedHashMap<>();
        f.put( "id", id );
        f.put( "error", error );
        failed.add( f );
    }

    public int succeededCount() {
        return succeeded.size();
    }

    public int failedCount() {
        return failed.size();
    }

    /**
     * @param attempted        total ids in the request (denominator of the message)
     * @param completionPhrase noun + past-tense verb, e.g. {@code "users locked"},
     *                         {@code "keys revoked"}, {@code "proposals approved"}
     */
    public Map< String, Object > toResponseBody( final int attempted, final String completionPhrase ) {
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "succeeded", succeeded );
        result.put( "failed", failed );
        result.put( "status", "completed" );
        result.put( "message", succeeded.size() + " of " + attempted + " " + completionPhrase );
        return result;
    }
}
