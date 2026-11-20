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

/**
 * Limits, property names and warning texts shared by the page API classes ({@link PageResource},
 * {@link PageSaveHandler}, {@link PageReadHandler}, {@code CollabResource}), so none of them reaches back into
 * another for a constant.
 */
final class PageApiLimits {

    private PageApiLimits() {
    }

    /** Warning code of a save that was stored but whose post-save processing failed. */
    static final String POST_SAVE_FAILED_CODE = "post_save_failed";

    /** Generic warning text for it: the cause stays in the server log (D5). */
    static final String POST_SAVE_FAILED_MESSAGE =
            "Saved; post-save processing failed and some derived data (search index, references) may lag.";

    /** Upper bound on {@code limit} for the similar-pages endpoint. */
    static final int MAX_SIMILAR_LIMIT = 20;

    /**
     * D21: maximum permitted page-name length. Names longer than this are rejected with
     * a 400 before they reach the storage layer (which would otherwise fail with an
     * absolute filesystem path in the message — see D5). 200 is generous for any
     * reasonable wiki name and still well under typical filesystem path limits.
     */
    static final int MAX_PAGE_NAME_LENGTH = 200;

    /**
     * D22: maximum permitted page body in bytes (UTF-8). Defaults to 256 KB; can be raised
     * via the {@code wikantik.api.maxPageBytes} property.
     */
    static final String PROP_MAX_PAGE_BYTES = "wikantik.api.maxPageBytes";
    static final int DEFAULT_MAX_PAGE_BYTES = 256 * 1024;

    /**
     * D20: opt-in strict-mode property. When {@code true}, PUT requests must supply
     * {@code expectedVersion}; otherwise the request is rejected with a 400 to avoid
     * silently overwriting a concurrent update. Defaults to {@code false} for backward
     * compatibility.
     */
    static final String PROP_REQUIRE_EXPECTED_VERSION = "wikantik.api.write.requireExpectedVersion";
}
