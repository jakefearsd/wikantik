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
package com.wikantik.work.mcp;

import com.wikantik.work.Actor;
import com.wikantik.work.Change;
import com.wikantik.work.EffectivePolicy;
import com.wikantik.work.WorkChangesService.ChangeView;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Changes as the work tools show them and the arguments that name one. People are shown by display name and
 * agents by name; a change is named by its number ({@code change}), which is only ever usable by a caller who can
 * see its item.
 */
final class WorkMcpChanges {

    private WorkMcpChanges() {
    }

    /** The {@code change} argument: the change's number. */
    static long id( final Map< String, Object > args ) {
        return WorkMcpArgs.idArg( args, "change" );
    }

    /** A required true-or-false argument. */
    static boolean requiredBoolean( final Map< String, Object > args, final String key ) {
        final Object v = args.get( key );
        if ( v == null ) {
            throw new WorkValidationException( key, "is required." );
        }
        if ( v instanceof Boolean b ) {
            return b;
        }
        throw new WorkValidationException( key, "must be true or false." );
    }

    /** One change, read fresh for the actor after a write. */
    static Map< String, Object > one( final WorkService svc, final WorkDirectory directory, final Actor actor,
                                      final long changeId ) throws SQLException {
        return change( WorkMcpJson.forActor( svc, directory, actor ), svc.changes().view( actor, changeId ) );
    }

    static List< Map< String, Object > > list( final WorkMcpJson names, final List< ChangeView > views ) {
        return views.stream().map( v -> change( names, v ) ).toList();
    }

    static Map< String, Object > change( final WorkMcpJson names, final ChangeView v ) {
        final Change c = v.change();
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "id", c.id() );
        o.put( "item", v.itemKey() );
        o.put( "product", v.productKey() );
        o.put( "branch", c.branch() );
        o.put( "base_branch", c.baseBranch() );
        o.put( "head_sha", c.headSha() );
        o.put( "review_url", c.reviewUrl() );
        o.put( "state", c.state().wire() );
        o.put( "opened_by", who( names, c.openedBy(), c.openedByAgentId() ) );
        o.put( "reviewed_by", who( names, c.reviewedBy(), c.reviewedByAgentId() ) );
        o.put( "approved_for_head", c.approvedForHead() );
        o.put( "merge_kind", c.mergeKind() == null ? null : c.mergeKind().wire() );
        o.put( "merged_sha", c.mergedSha() );
        o.put( "merged_by", who( names, c.mergedBy(), c.mergedByAgentId() ) );
        return o;
    }

    static Map< String, Object > effective( final EffectivePolicy p ) {
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "autonomy", p.level().wire() );
        o.put( "allow_direct", p.directAllowed() );
        o.put( "done_means", p.doneWhen().wire() );
        return o;
    }

    private static String who( final WorkMcpJson names, final String stored, final Long agentId ) {
        if ( stored == null ) {
            return null;
        }
        return agentId == null ? names.person( stored ) : names.agent( agentId, stored );
    }
}
