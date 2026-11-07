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
package com.wikantik.api.pages;

import com.wikantik.api.core.Page;

/**
 * A precondition evaluated by the page manager while it holds the per-page save lock, immediately before the write, so
 * that nothing can land between the check and the write. The page manager may also evaluate it earlier (before the
 * pre-save filters, to fail fast), so a guard must be side-effect free; the check under the lock is authoritative.
 */
@FunctionalInterface
public interface SaveGuard {

    /** A guard that never refuses. */
    SaveGuard NONE = ( page, text ) -> { };

    /**
     * @param current     the latest stored page, or {@code null} when the page does not exist
     * @param currentText the latest stored text (frontmatter included), or {@code null} when the page does not exist
     * @throws VersionConflictException when the save must be refused
     */
    void check( Page current, String currentText ) throws VersionConflictException;
}
