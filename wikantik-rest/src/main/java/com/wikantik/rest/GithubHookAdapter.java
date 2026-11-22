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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.work.CodeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Translates a GitHub webhook delivery ({@code X-GitHub-Event} and the JSON body) into neutral {@link CodeEvent}s. A pure
 * function: a body missing what its kind needs, or of an unexpected shape, gives an empty list and never an exception.
 * Dismissed and commented reviews, tags, branch deletions and pings give nothing (P5-11).
 *
 * <p>Every event carries the host's time of the event itself, from this field (the store orders lifecycle events,
 * heads and approvals by it, never by arrival; a delivery whose event has no usable time is rejected by
 * {@link HookIntake}, never stamped with the time it arrived):
 * <table>
 * <caption>Where each event's time comes from</caption>
 * <tr><th>{@code X-GitHub-Event}</th><th>action</th><th>event</th><th>time</th><th>fallback</th></tr>
 * <tr><td>push</td><td></td><td>PUSH</td><td>{@code repository.pushed_at} (seconds since 1970)</td>
 *     <td>{@code head_commit.timestamp}</td></tr>
 * <tr><td>pull_request</td><td>opened</td><td>REVIEW_OPENED</td><td>{@code pull_request.created_at}</td>
 *     <td>{@code pull_request.updated_at}</td></tr>
 * <tr><td>pull_request</td><td>reopened</td><td>REVIEW_REOPENED</td><td>{@code pull_request.updated_at}</td>
 *     <td></td></tr>
 * <tr><td>pull_request</td><td>ready_for_review, review_requested</td><td>REVIEW_READY</td>
 *     <td>{@code pull_request.updated_at}</td><td></td></tr>
 * <tr><td>pull_request</td><td>synchronize</td><td>REVIEW_UPDATED</td><td>{@code pull_request.updated_at}</td>
 *     <td></td></tr>
 * <tr><td>pull_request</td><td>closed, {@code merged: false}</td><td>REVIEW_CLOSED</td>
 *     <td>{@code pull_request.closed_at}</td><td>{@code pull_request.updated_at}</td></tr>
 * <tr><td>pull_request</td><td>closed, {@code merged: true}</td><td>REVIEW_MERGED</td>
 *     <td>{@code pull_request.merged_at}</td><td>{@code closed_at}, then {@code updated_at}</td></tr>
 * <tr><td>pull_request_review</td><td>submitted, state approved</td><td>REVIEW_APPROVED</td>
 *     <td>{@code review.submitted_at}</td><td></td></tr>
 * </table>
 * GitHub has no field of its own for a reopening, a review request or a new head; {@code updated_at} is set by that
 * action, so it is the action's time unless the payload was generated after a later update of the pull request.
 */
final class GithubHookAdapter {

    private static final Logger LOG = LogManager.getLogger( GithubHookAdapter.class );
    private static final Set< String > KINDS = Set.of( "push", GithubPullRequests.PULL_REQUEST, "pull_request_review", "ping" );
    private static final String BRANCH_PREFIX = "refs/heads/";

    private GithubHookAdapter() {
    }

    /** The events a delivery holds; {@code eventHeader} is {@code X-GitHub-Event}, which may be null. */
    static List< CodeEvent > translate( final String eventHeader, final JsonObject body ) {
        try {
            if ( body == null || eventHeader == null ) {
                return List.of();
            }
            return switch ( eventHeader ) {
                case "push" -> push( body );
                case GithubPullRequests.PULL_REQUEST -> GithubPullRequests.pullRequest( body );
                case "pull_request_review" -> GithubPullRequests.review( body );
                default -> List.of();
            };
        } catch ( final RuntimeException e ) {
            LOG.warn( "github hook translation failed event={} cause={}", HookJson.loggable( eventHeader, KINDS ),
                e.getClass().getSimpleName() );
            return List.of();
        }
    }

    private static List< CodeEvent > push( final JsonObject b ) {
        final String ref = HookJson.str( b, "ref", HookJson.BRANCH + BRANCH_PREFIX.length() );
        final String after = HookJson.str( b, "after", HookJson.SHA );
        final JsonObject repository = HookJson.obj( b, "repository" );
        final String repo = HookJson.str( repository, "full_name", HookJson.NAME );
        if ( ref == null || !ref.startsWith( BRANCH_PREFIX ) || ref.length() == BRANCH_PREFIX.length()
                || after == null || repo == null || HookJson.isTrue( b, "deleted" ) ) {
            return List.of();
        }
        return List.of( new CodeEvent( CodeEvent.Kind.PUSH, repo, HookJson.str( repository, "default_branch", HookJson.BRANCH ),
            ref.substring( BRANCH_PREFIX.length() ), null, HookJson.str( b, "before", HookJson.SHA ), after,
            pushedAt( b, repository ), commits( HookJson.arr( b, "commits" ) ), null,
            null, null ) );
    }

    private static List< CodeEvent.Commit > commits( final JsonArray array ) {
        final List< CodeEvent.Commit > out = new ArrayList<>();
        if ( array == null ) {
            return out;
        }
        for ( final JsonElement element : array ) {
            if ( out.size() >= HookJson.MAX_COMMITS ) {
                break;
            }
            final JsonObject c = element.isJsonObject() ? element.getAsJsonObject() : null;
            final String sha = HookJson.str( c, "id", HookJson.SHA );
            if ( sha != null ) {
                out.add( new CodeEvent.Commit( sha, HookJson.str( c, "message", HookJson.MESSAGE ), HookJson.time( c, "timestamp" ),
                    HookJson.bool( c, "distinct", false ), HookJson.person( HookJson.obj( c, "author" ), "username" ) ) );
            }
        }
        return out;
    }

    /**
     * When the push happened: the repository's {@code pushed_at}, which GitHub sets to this push's time. The head
     * commit's own time is only the fallback: a commit made earlier and pushed later carries its making time, which
     * would let a later push sort before a pull request event it followed (the applier orders heads by host time).
     */
    private static java.time.Instant pushedAt( final JsonObject b, final JsonObject repository ) {
        final java.time.Instant pushed = HookTimes.epochOrTime( repository, "pushed_at" );
        return pushed != null ? pushed : HookJson.time( HookJson.obj( b, "head_commit" ), "timestamp" );
    }

    static String repo( final JsonObject b ) {
        return HookJson.str( HookJson.obj( b, "repository" ), "full_name", HookJson.NAME );
    }

    static String defaultBranch( final JsonObject b ) {
        return HookJson.str( HookJson.obj( b, "repository" ), "default_branch", HookJson.BRANCH );
    }
}
