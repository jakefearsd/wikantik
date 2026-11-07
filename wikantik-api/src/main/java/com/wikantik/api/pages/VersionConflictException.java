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

import com.wikantik.api.exceptions.WikiException;

/**
 * Thrown when an optimistic locking check fails during a page save.
 */
public class VersionConflictException extends WikiException {

    private static final long serialVersionUID = 1L;

    /** The message of a conflict raised because the page the save expected no longer exists (renamed or deleted). */
    public static final String PAGE_GONE = "page-gone";

    private final int currentVersion;
    private final int expectedVersion;
    private final boolean hashConflict;
    private final boolean pageGone;

    public VersionConflictException( final String pageName,
                                     final int currentVersion,
                                     final int expectedVersion,
                                     final boolean hashConflict ) {
        super( hashConflict
                ? "Content hash conflict: page '" + pageName + "' content has changed since you last read it."
                : "Version conflict: page '" + pageName + "' is at version " + currentVersion
                        + " but expectedVersion was " + expectedVersion );
        this.currentVersion = currentVersion;
        this.expectedVersion = expectedVersion;
        this.hashConflict = hashConflict;
        this.pageGone = false;
    }

    private VersionConflictException( final String message, final int currentVersion, final int expectedVersion ) {
        super( message );
        this.currentVersion = currentVersion;
        this.expectedVersion = expectedVersion;
        this.hashConflict = false;
        this.pageGone = true;
    }

    /**
     * A conflict for a save that expected {@code expectedVersion} of a page that no longer exists: it was renamed or
     * deleted away, so the save must not recreate it. The message is {@link #PAGE_GONE}; the current version is 0.
     */
    public static VersionConflictException pageGone( final int expectedVersion ) {
        return new VersionConflictException( PAGE_GONE, 0, expectedVersion );
    }

    public int getCurrentVersion() { return currentVersion; }
    public int getExpectedVersion() { return expectedVersion; }
    public boolean isHashConflict() { return hashConflict; }

    /** True for a {@link #pageGone} conflict: the page the save expected no longer exists. */
    public boolean isPageGone() { return pageGone; }
}
