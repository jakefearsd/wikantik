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
package com.wikantik.audit;

import java.util.List;

/** Persistence boundary for the audit log. Append-only. */
public interface AuditRepository {

    /** Chain head snapshot: the seq and row_hash of the last persisted row. */
    record ChainHead( long lastSeq, String lastHash ) {}

    /** Returns the current chain head (genesis if empty). Implementations that
     *  serialize writes (the JDBC impl) read this under the chain lock. */
    ChainHead chainHead();

    /** Appends a batch, assigning contiguous seq and chain hashes atomically. */
    void append( List<AuditEntry> entries );

    /** Paged, filtered query, newest-first. */
    List<PersistedAuditEntry> query( AuditQuery query );

    /** Walks rows in seq order recomputing the chain. Returns the seq of the
     *  first broken row, or empty if the chain is intact. */
    java.util.Optional<Long> verifyChain( long fromSeq, long toSeq );
}
