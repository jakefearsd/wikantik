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
 * The cursors of a pull (section 6.2 as amended, ruling P4): the text version {@code since}, and the sequence numbers the
 * client has seen of meta ops, presence and events. A pull is answered at once when any stream is ahead of its cursor;
 * {@link Long#MAX_VALUE} means "not interested in this stream".
 */
public record PullCursor( int since, long metaSince, long presenceSince, long eventsSince ) {

    /** Text only (and presence from {@code presenceSince}); meta ops and events are not asked for. */
    public static PullCursor text( final int since, final long presenceSince ) {
        return new PullCursor( since, Long.MAX_VALUE, presenceSince, Long.MAX_VALUE );
    }
}
