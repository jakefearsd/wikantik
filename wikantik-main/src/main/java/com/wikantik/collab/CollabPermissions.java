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

    /**
     * The author key the participant's wiki session resolves to now, or null when the port cannot tell. A logout that
     * leaves the same session signed in as someone else (ruling Q13) evicts the participant who joined as the first.
     */
    default String authorKeyOf( final Object participantSession ) {
        return null;
    }

    /**
     * Whether the participant is an administrator ({@code AllPermission}): ruling F1 lets administrators into the frozen
     * draft of a deleted page. Ports without the notion answer false.
     */
    default boolean isAdmin( final Object participantSession ) {
        return false;
    }

    /**
     * Ruling F1: whether the participant could {@code view} and {@code edit} {@code pageName} if its text were
     * {@code baseText}, judged by the wiki's policy and by the access rules ({@code [{ALLOW …}]}) in that text, read as
     * the wiki reads a page's rules. The draft of a deleted page is guarded by its last-known text this way, so the
     * page's protection outlives the page. False when the rules cannot be evaluated; the default (a port that cannot
     * read access rules) refuses.
     */
    default boolean mayAccessAsIf( final Object participantSession, final String pageName, final String baseText ) {
        return false;
    }

    /**
     * Ruling F3': whether {@code text}, read as {@code pageName}'s text by the wiki's own access-rule parse, holds access
     * rules (a non-empty ACL) or is locked by rules it cannot read. A frozen draft's guard that restricts makes the draft
     * publishable only by an administrator. A port that cannot evaluate it answers true (the default): fail closed.
     */
    default boolean restricts( final String pageName, final String text ) {
        return true;
    }
}
