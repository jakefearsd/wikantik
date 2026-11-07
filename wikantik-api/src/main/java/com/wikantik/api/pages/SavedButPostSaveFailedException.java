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
 * Thrown by a save when the page was persisted but the handling that follows the write (post-save filters, events,
 * reindexing) failed. The page is stored: {@link #outcome()} reports the version this call wrote, and the cause is the
 * exception the post-save handling threw. A caller that only cares whether the save as a whole succeeded treats it like
 * any other {@link WikiException}; a caller that must know what was written (a collaborative publish) reads the outcome.
 */
public class SavedButPostSaveFailedException extends WikiException {

    private static final long serialVersionUID = 1L;

    private final transient SaveOutcome outcome;

    /**
     * @param pageName the page that was saved
     * @param outcome  what the save wrote
     * @param cause    the exception the post-save handling threw
     */
    public SavedButPostSaveFailedException( final String pageName, final SaveOutcome outcome, final Throwable cause ) {
        super( "Page '" + pageName + "' was saved as version " + outcome.version() + ", but the handling after the save "
                + "failed: " + cause.getMessage(), cause );
        this.outcome = outcome;
    }

    /** What the save wrote: the page is stored at {@code outcome().version()}. */
    public SaveOutcome outcome() {
        return outcome;
    }
}
