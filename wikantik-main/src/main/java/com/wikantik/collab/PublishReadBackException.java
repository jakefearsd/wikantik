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

/**
 * A publish whose save stored {@link #savedVersion()} but whose text could not be read back. The save landed, so the
 * version is the publisher's own write: the session finishes the publish from that version's text (read again), also
 * when a save filter rewrote it, and never merges it as an outside write (that would apply the publisher's edits a
 * second time). See {@link CollabPageWriter#publish}.
 */
public final class PublishReadBackException extends WikiException {

    private static final long serialVersionUID = 1L;

    /** The page version the save wrote; 0 when the save reported none. */
    private final int savedVersion;

    public PublishReadBackException( final String message, final int savedVersion, final Throwable cause ) {
        super( message, cause );
        this.savedVersion = savedVersion;
    }

    /** The page version the save wrote; 0 when the save reported none. */
    public int savedVersion() {
        return savedVersion;
    }
}
