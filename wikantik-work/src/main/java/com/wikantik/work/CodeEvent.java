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
package com.wikantik.work;

import java.time.Instant;
import java.util.List;

/**
 * One thing the code host reported, in a form that does not depend on the host: a push, or a step in a pull
 * request's life. The adapters (GitHub, generic JSON) translate into this; the applier never sees a host's payload.
 * {@code actor} is the reviewer for {@code REVIEW_APPROVED}, the merger for {@code REVIEW_MERGED} and the pusher the
 * host asserts for {@code PUSH} (a push is attributed to it, never to a commit's author); {@code at} is
 * the host's time of the event, taken from the event's own field (the GitHub adapter's Javadoc has the table). Every
 * event needs one: a delivery holding an event with a null {@code at} is rejected, and the applier ignores one.
 */
public record CodeEvent( Kind kind, String repo, String defaultBranch, String branch, String baseBranch,
                         String before, String after, Instant at, List< Commit > commits, Review review,
                         Person actor, String mergedSha ) {

    /** What happened. */
    public enum Kind {
        PUSH, REVIEW_OPENED, REVIEW_READY, REVIEW_UPDATED, REVIEW_APPROVED, REVIEW_MERGED, REVIEW_CLOSED, REVIEW_REOPENED
    }

    /** One commit of a push; {@code distinct} is the host's "first time this commit reached the repository". */
    public record Commit( String sha, String message, Instant at, boolean distinct, Person author ) { }

    /** A pull request (or merge request). */
    public record Review( String number, String url, String title, String headSha, boolean draft, Person author ) { }

    /** A person as the code host names them; the email, when there is one, is only ever used to find a wiki user. */
    public record Person( String username, String name, String email ) { }

    public CodeEvent {
        commits = commits == null ? List.of() : List.copyOf( commits );
    }
}
