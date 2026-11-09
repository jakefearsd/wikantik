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
package com.wikantik.its;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The draft purge is global ({@code purgeAllDrafts}), so a class running concurrently with the sequential editor
 * suites must never run it: its afterEach would evict the collab session of whichever editor test is mid-flight
 * (the editor flips read-only, loses focus, its completion list closes).
 */
class CollabDraftCleanupExtensionTest {

    @Execution( ExecutionMode.CONCURRENT )
    static class ConcurrentClass { }

    @Execution( ExecutionMode.SAME_THREAD )
    static class ExplicitlySequentialClass { }

    static class DefaultClass { }

    @Test
    void aConcurrentClassNeverPurgesDrafts() {
        assertFalse( CollabDraftCleanupExtension.mayPurgeAfter( ConcurrentClass.class ) );
    }

    @Test
    void sequentialClassesPurgeDrafts() {
        assertTrue( CollabDraftCleanupExtension.mayPurgeAfter( DefaultClass.class ) );
        assertTrue( CollabDraftCleanupExtension.mayPurgeAfter( ExplicitlySequentialClass.class ) );
    }
}
