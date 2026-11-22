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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.work.CodeEvent;

import java.util.List;
import java.util.Map;

/** The pull-request half of {@link GithubHookAdapter}: opened, ready, updated, merged, closed and approved. */
final class GithubPullRequests {

    static final String PULL_REQUEST = "pull_request";
    private static final Map< String, CodeEvent.Kind > OPENING = Map.of(
        "opened", CodeEvent.Kind.REVIEW_OPENED, "reopened", CodeEvent.Kind.REVIEW_REOPENED,
        "ready_for_review", CodeEvent.Kind.REVIEW_READY, "review_requested", CodeEvent.Kind.REVIEW_READY,
        "synchronize", CodeEvent.Kind.REVIEW_UPDATED );

    private GithubPullRequests() {
    }

    static List< CodeEvent > pullRequest( final JsonObject b ) {
        final String action = HookJson.str( b, "action", HookJson.NAME );
        final JsonObject pr = HookJson.obj( b, PULL_REQUEST );
        if ( action == null || pr == null ) {
            return List.of();
        }
        final CodeEvent.Kind kind = "closed".equals( action ) ? closedKind( pr ) : OPENING.get( action );
        if ( kind == null ) {
            return List.of();
        }
        final CodeEvent.Review review = review( pr, HookJson.str( HookJson.obj( pr, "head" ), "sha", HookJson.SHA ) );
        final String branch = HookJson.str( HookJson.obj( pr, "head" ), "ref", HookJson.BRANCH );
        if ( review == null || branch == null ) {
            return List.of();
        }
        final boolean merged = kind == CodeEvent.Kind.REVIEW_MERGED;
        final JsonObject mergedBy = HookJson.obj( pr, "merged_by" );
        return List.of( new CodeEvent( kind, GithubHookAdapter.repo( b ), GithubHookAdapter.defaultBranch( b ), branch,
            HookJson.str( HookJson.obj( pr, "base" ), "ref", HookJson.BRANCH ), null, null, at( kind, pr ),
            List.of(), review, merged ? HookJson.login( HookJson.str( mergedBy, "login", HookJson.NAME ) ) : null,
            merged ? HookJson.str( pr, "merge_commit_sha", HookJson.SHA ) : null ) );
    }

    /**
     * The event's own time (see the table in {@link GithubHookAdapter}): an opening's {@code created_at}, a close's
     * {@code closed_at}, a merge's {@code merged_at}, falling back to {@code updated_at}, which is also the time of a
     * reopening, a review request, ready for review and a new head. Null when none is usable: the delivery is then
     * rejected.
     */
    private static java.time.Instant at( final CodeEvent.Kind kind, final JsonObject pr ) {
        final java.time.Instant own = switch ( kind ) {
            case REVIEW_OPENED -> HookJson.time( pr, "created_at" );
            case REVIEW_CLOSED -> HookJson.time( pr, "closed_at" );
            case REVIEW_MERGED -> firstOf( HookJson.time( pr, "merged_at" ), pr, "closed_at" );
            default -> null;
        };
        return firstOf( own, pr, "updated_at" );
    }

    private static java.time.Instant firstOf( final java.time.Instant own, final JsonObject pr, final String fallback ) {
        return own != null ? own : HookJson.time( pr, fallback );
    }

    /** {@code closed} is a merge only when {@code merged} is the JSON boolean true; a missing flag is not a merge. */
    private static CodeEvent.Kind closedKind( final JsonObject pr ) {
        final JsonElement merged = pr.get( "merged" );
        if ( merged == null || !merged.isJsonPrimitive() || !merged.getAsJsonPrimitive().isBoolean() ) {
            return null;
        }
        return merged.getAsBoolean() ? CodeEvent.Kind.REVIEW_MERGED : CodeEvent.Kind.REVIEW_CLOSED;
    }

    private static CodeEvent.Review review( final JsonObject pr, final String headSha ) {
        final String number = HookJson.number( pr, "number" );
        if ( number == null ) {
            return null;
        }
        return new CodeEvent.Review( number, HookJson.str( pr, "html_url", HookJson.URL ), HookJson.str( pr, "title", HookJson.TITLE ),
            headSha, HookJson.isTrue( pr, "draft" ), HookJson.login( HookJson.str( HookJson.obj( pr, "user" ), "login", HookJson.NAME ) ) );
    }

    /** An approval: only {@code submitted} with state {@code approved}; the reviewed commit is the head. */
    static List< CodeEvent > review( final JsonObject b ) {
        final JsonObject review = HookJson.obj( b, "review" );
        final JsonObject pr = HookJson.obj( b, PULL_REQUEST );
        final String state = HookJson.str( review, "state", HookJson.NAME );
        if ( !"submitted".equals( HookJson.str( b, "action", HookJson.NAME ) ) || state == null
                || !"approved".equalsIgnoreCase( state ) || pr == null ) {
            return List.of();
        }
        final CodeEvent.Review r = review( pr, HookJson.str( review, "commit_id", HookJson.SHA ) );
        final String branch = HookJson.str( HookJson.obj( pr, "head" ), "ref", HookJson.BRANCH );
        if ( r == null || r.headSha() == null || branch == null ) {
            return List.of();
        }
        return List.of( new CodeEvent( CodeEvent.Kind.REVIEW_APPROVED, GithubHookAdapter.repo( b ), GithubHookAdapter.defaultBranch( b ), branch,
            HookJson.str( HookJson.obj( pr, "base" ), "ref", HookJson.BRANCH ), null, null,
            HookJson.time( review, "submitted_at" ), List.of(), r,
            HookJson.login( HookJson.str( HookJson.obj( review, "user" ), "login", HookJson.NAME ) ), null ) );
    }
}
