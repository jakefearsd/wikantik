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
package com.wikantik.collab.model;

/**
 * The answer to a publish (section 7.3). {@code status}:
 * <ul>
 *   <li>200: saved as {@code pageVersion}; {@code unchanged} true when there was nothing to save (E21);</li>
 *   <li>400 {@code empty} (E1); 403 {@code not-your-participant};</li>
 *   <li>409 {@code rejoin}, {@code not-synced} (the version is not one the session has issued) or
 *       {@code publish-merged-retry} (an outside save was merged first: sync and publish again, E20);</li>
 *   <li>413 {@code oversize} (the draft is over {@code maxPageBytes}, section 7.4);</li>
 *   <li>422 {@code writer-refused}: the save pipeline refused the text; {@code failure} is the exception
 *       ({@code FrontmatterValidationException}, {@code ContentValidationException}, {@code AccessRuleShapeException}
 *       or another {@code WikiException}), from which the HTTP layer builds the same 422 body as {@code PageResource}
 *       (E19);</li>
 *   <li>423 {@code publish-in-progress} (E18); 500 {@code writer-failed} (the save pipeline threw a runtime exception and
 *       left no version); 503 {@code store} (a store write failed; a saved version is finished by the pending-publish
 *       rule, A2).</li>
 * </ul>
 * {@code pageVersion} is the session's base page version after the call. {@code failure} is null except on 422 and
 * 500.
 */
public record PublishResponse( int status, int pageVersion, String reason, boolean unchanged, Exception failure ) {

    /** A response without a failure cause. */
    public PublishResponse( final int status, final int pageVersion, final String reason, final boolean unchanged ) {
        this( status, pageVersion, reason, unchanged, null );
    }
}
