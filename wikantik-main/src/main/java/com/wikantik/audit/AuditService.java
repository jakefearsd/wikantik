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
import java.util.Optional;

/** Records audit entries asynchronously and answers audit queries. */
public interface AuditService {

    /** Non-blocking. Enqueues an entry for the writer. Overflow is dropped and
     *  counted (see {@link #droppedCount()}); this method never throws. */
    void record( AuditEntry entry );

    /** Paged, filtered, newest-first. */
    List<PersistedAuditEntry> query( AuditQuery query );

    /** Verifies the hash chain; returns the first broken seq, or empty if intact. */
    Optional<Long> verifyChain( long fromSeq, long toSeq );

    /** Count of entries dropped because the queue was full. Exposed as a metric. */
    long droppedCount();
}
