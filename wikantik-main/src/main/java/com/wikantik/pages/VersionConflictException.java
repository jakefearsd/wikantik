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
package com.wikantik.pages;

import com.wikantik.api.exceptions.WikiException;

/**
 * Thrown when an optimistic locking check fails during a page save.
 */
public class VersionConflictException extends WikiException {

    private final int currentVersion;
    private final int expectedVersion;
    private final boolean hashConflict;

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
    }

    public int getCurrentVersion() { return currentVersion; }
    public int getExpectedVersion() { return expectedVersion; }
    public boolean isHashConflict() { return hashConflict; }
}
