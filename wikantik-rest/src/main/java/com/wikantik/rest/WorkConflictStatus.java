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

import com.wikantik.work.WorkConflictException;
import jakarta.servlet.http.HttpServletResponse;

/** The HTTP status a {@link WorkConflictException} reason is answered with. */
final class WorkConflictStatus {

    private WorkConflictStatus() {
    }

    /**
     * 413 for the attachment size refusals ({@code too_large}, {@code item_full}, {@code reporter_full}), 429 for a
     * reporter's filing cap ({@code intake_limit}), 409 for every other reason.
     */
    static int of( final String reason ) {
        if ( WorkConflictException.TOO_LARGE.equals( reason ) || WorkConflictException.ITEM_FULL.equals( reason )
                || WorkConflictException.REPORTER_FULL.equals( reason ) ) {
            return 413;
        }
        return WorkConflictException.INTAKE_LIMIT.equals( reason ) ? 429 : HttpServletResponse.SC_CONFLICT;
    }
}
