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
import java.util.Objects;

/**
 * One entry of a session's event stream (section 6.2: {@code events}/{@code eventSeq}). {@code seq} increases by one per
 * event. {@code type} is one of {@code published} ({@code by}, {@code pageVersion}), {@code publish-failed}
 * ({@code by}, {@code status}, {@code reason}), {@code external-change} ({@code source}, {@code pageVersion},
 * {@code version}: the collab head right after the merge, {@code ranges}: {@code [from, to]} pairs in that document,
 * {@code conflictFields}, and {@code recreated: true} for a page recreated under a frozen draft), {@code oversize}
 * ({@code bytes}, {@code limit}), {@code participant-joined} ({@code participant}: id, display name, colour),
 * {@code participant-left} ({@code participantId}, {@code reason}: left, timeout or evicted), {@code evicted}
 * ({@code participantId}, {@code reason}: lost-edit, signed-out or account-deleted), {@code page-renamed}
 * ({@code to}), {@code page-deleted} and {@code draft-discarded}. {@code fields} never holds a login: people are named by their public
 * display name.
 */
public record CollabEvent( long seq, String type, Map< String, Object > fields ) {
    public CollabEvent {
        ModelChecks.nonNegative( seq, "seq" );
        ModelChecks.nonBlank( type, "type" );
        fields = Map.copyOf( Objects.requireNonNull( fields, "fields" ) );
    }
}
