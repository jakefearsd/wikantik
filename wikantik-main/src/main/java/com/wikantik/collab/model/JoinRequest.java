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

import java.util.Map;

/**
 * A join (section 6.2 as amended by A5, A11 and section 7.2's "same clientID").
 *
 * @param authorKey     the caller's author key; the participant is bound to it (A6)
 * @param priorClientId the clientID the caller held before (a rejoin after its participant expired), or null. It is
 *                      reissued when the session still knows it as this author's and no live participant holds it
 * @param wikiSession   the caller's wiki {@code Session} (A5), opaque to the session core; may be null in tests
 * @param displayName   the public name shown to other participants; null shows the author key
 * @param initialText   a template or restored text (A11), used only when this join is the first into a freshly
 *                      created session; null otherwise
 * @param initialMeta   the frontmatter that goes with {@code initialText}, under the same rule; null otherwise
 */
public record JoinRequest( String authorKey, String priorClientId, Object wikiSession, String displayName,
                           String initialText, Map< String, Object > initialMeta ) {
    public JoinRequest {
        ModelChecks.nonBlank( authorKey, "authorKey" );
        if( initialMeta != null ) {
            initialMeta = ModelChecks.copyMeta( initialMeta, "initialMeta" );
        }
    }

    /** A plain join: no prior clientID, no initial content. */
    public static JoinRequest of( final String authorKey, final Object wikiSession, final String displayName ) {
        return new JoinRequest( authorKey, null, wikiSession, displayName, null, null );
    }
}
