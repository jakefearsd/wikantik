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

/**
 * Port to the wiki's authorization. {@code participantSession} is the wiki {@code Session} (A5); it is typed
 * {@code Object} so this port stays engine-free, and the Phase 1 adapter casts.
 */
public interface CollabPermissions {

    boolean canEdit( Object participantSession, String pageName );

    boolean canCreate( Object participantSession, String pageName );

    /**
     * Whether the participant's wiki session is still signed in (A13): after a logout its participants are evicted
     * with {@code signed-out}, keeping their edits on the client. Ports without a notion of sign-in answer true.
     */
    default boolean signedIn( final Object participantSession ) {
        return true;
    }
}
