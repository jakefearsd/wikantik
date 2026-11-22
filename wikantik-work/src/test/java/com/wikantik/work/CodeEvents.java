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

/** Test builders for neutral code-host events; every time is a fixed instant. */
final class CodeEvents {

    static final String A = "aaaaaaa1";
    static final String B = "bbbbbbb2";
    static final String C = "ccccccc3";
    static final String M = "eeeeeee5";

    static final CodeEvent.Person ANA = new CodeEvent.Person( "anagh", "Ana A", "ana@example.com" );
    static final CodeEvent.Person MIA = new CodeEvent.Person( "miagh", "Mia M", "mia@example.com" );
    static final CodeEvent.Person OCTO = new CodeEvent.Person( "octocat", "Octo Cat", "1+octocat@users.noreply.github.com" );

    private CodeEvents() {
    }

    static Instant at( final String hhmm ) {
        return Instant.parse( "2026-03-01T" + hhmm + ":00Z" );
    }

    static CodeEvent.Commit commit( final String sha, final String message, final Instant at, final boolean distinct,
                                    final CodeEvent.Person author ) {
        return new CodeEvent.Commit( sha, message, at, distinct, author );
    }

    /** A push to {@code branch}; {@code after} is the new head. */
    static CodeEvent push( final String branch, final String after, final Instant at,
                           final CodeEvent.Commit... commits ) {
        return new CodeEvent( CodeEvent.Kind.PUSH, "acme/wk", "main", branch, null, "0000000", after, at,
            List.of( commits ), null, null, null );
    }

    static CodeEvent review( final CodeEvent.Kind kind, final String branch, final String number, final String head,
                             final String title, final Instant at, final boolean draft, final CodeEvent.Person author,
                             final CodeEvent.Person actor ) {
        return new CodeEvent( kind, "acme/wk", "main", branch, "main", null, null, at, List.of(),
            new CodeEvent.Review( number, "https://github.com/acme/wk/pull/" + number, title, head, draft, author ),
            actor, null );
    }

    static CodeEvent opened( final String branch, final String number, final String head, final Instant at ) {
        return review( CodeEvent.Kind.REVIEW_OPENED, branch, number, head, "A title", at, false, ANA, ANA );
    }

    static CodeEvent openedDraft( final String branch, final String number, final String head, final Instant at ) {
        return review( CodeEvent.Kind.REVIEW_OPENED, branch, number, head, "A title", at, true, ANA, ANA );
    }

    static CodeEvent ready( final String branch, final String number, final String head, final Instant at ) {
        return review( CodeEvent.Kind.REVIEW_READY, branch, number, head, "A title", at, false, ANA, ANA );
    }

    static CodeEvent updated( final String branch, final String number, final String head, final Instant at ) {
        return review( CodeEvent.Kind.REVIEW_UPDATED, branch, number, head, "A title", at, false, ANA, ANA );
    }

    static CodeEvent approved( final String branch, final String number, final String head, final Instant at,
                               final CodeEvent.Person reviewer ) {
        return review( CodeEvent.Kind.REVIEW_APPROVED, branch, number, head, "A title", at, false, ANA, reviewer );
    }

    static CodeEvent reopened( final String branch, final String number, final String head, final Instant at ) {
        return review( CodeEvent.Kind.REVIEW_REOPENED, branch, number, head, "A title", at, false, ANA, ANA );
    }

    static CodeEvent closed( final String branch, final String number, final String head, final Instant at ) {
        return review( CodeEvent.Kind.REVIEW_CLOSED, branch, number, head, "A title", at, false, ANA, ANA );
    }

    static CodeEvent merged( final String branch, final String number, final String head, final String mergedSha,
                             final Instant at, final CodeEvent.Person merger ) {
        final CodeEvent base = review( CodeEvent.Kind.REVIEW_MERGED, branch, number, head, "A title", at, false, ANA,
            merger );
        return new CodeEvent( base.kind(), base.repo(), base.defaultBranch(), base.branch(), base.baseBranch(), null,
            null, at, List.of(), base.review(), merger, mergedSha );
    }
}
