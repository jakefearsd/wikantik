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
package com.wikantik.connectors;

import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;

import java.util.List;

/**
 * The two terminal shapes of a poll that enumerates a whole source, shared by the token and web
 * connectors so the trusted/untrusted cursor contract is written once. Neither shape carries
 * tombstones: connectors that enumerate this way never derive them themselves.
 */
public final class SyncBatches {

    private SyncBatches() {}

    /**
     * A finished enumeration. When {@code trusted} the listing is a full snapshot: the cursor
     * becomes the item count and the batch is complete. Otherwise the items are still delivered but
     * the input cursor is kept and the batch is incomplete, so the orchestrator derives no
     * tombstones and retries next cycle.
     */
    public static SyncBatch finish( final List< SourceItem > items, final boolean trusted, final SyncCursor input ) {
        if ( !trusted ) {
            return new SyncBatch( items, List.of(), input, false );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    /** A poll that produced nothing usable: empty, incomplete, input cursor unchanged. */
    public static SyncBatch failed( final SyncCursor input ) {
        return new SyncBatch( List.of(), List.of(), input, false );
    }
}
