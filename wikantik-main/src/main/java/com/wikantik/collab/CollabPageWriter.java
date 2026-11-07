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
package com.wikantik.collab;

import com.wikantik.api.exceptions.WikiException;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;

import java.util.Optional;

/** Port to the wiki's page store as the collaboration session sees it. */
public interface CollabPageWriter {

    /** The latest saved state of the page, or empty when the page does not exist. */
    Optional< PageState > latest( String pageName );

    /** A specific saved version of the page, or empty when it does not exist. */
    Optional< PageState > version( String pageName, int version );

    /**
     * Saves a page, failing with {@code VersionConflictException} when {@code expectedVersion} is no longer the latest;
     * {@code expectedVersion} 0 means the page must not exist (see {@link PublishRequest}). A positive
     * {@code expectedVersion} on a page that no longer exists (renamed or deleted away) fails with the conflict
     * {@code VersionConflictException.pageGone} (message {@code page-gone}) and never recreates the page. A save that was
     * stored but then failed in its post-save handling returns the version it wrote.
     */
    PublishOutcome publish( PublishRequest request ) throws WikiException;
}
