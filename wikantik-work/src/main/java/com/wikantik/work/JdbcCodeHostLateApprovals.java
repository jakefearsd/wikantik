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

import com.wikantik.work.WorkCodeHostStore.HostWrite;
import com.wikantik.work.WorkCodeHostStore.ReviewFacts;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * A host approval of a merged change. It is recorded against the commit it approved, and its commit's first approval
 * writes one {@code change_reviewed} event, as it would have before the merge. When the host stamped it no later than
 * the merge, on the commit that was merged, and it is not the author's own (the caller drops those), the merge would
 * have been {@code reviewed} had it arrived in the host's order: a merge the code host recorded as {@code unreviewed}
 * becomes {@code reviewed} and its flag is withdrawn, in the same transaction and with that one event (its note says
 * so). Only a flag the code host set and no lead has acknowledged is withdrawn: a merge an agent or a person reported
 * carries no code-host flag, and an acknowledgement is a person's act, which no delivery undoes. An approval stamped
 * after the merge never withdraws anything.
 */
final class JdbcCodeHostLateApprovals {

    static final String NOTE = "approved before the merge; no longer outside policy";

    private final JdbcCodeHostRows rows;
    private final JdbcCodeHostHeads heads;

    JdbcCodeHostLateApprovals( final JdbcCodeHostRows rows, final JdbcCodeHostHeads heads ) {
        this.rows = rows;
        this.heads = heads;
    }

    HostWrite approve( final Connection conn, final Item item, final Change merged, final ReviewFacts f,
                       final CodeHostWho reviewer, final Actor by ) throws SQLException {
        if ( f.headSha() == null ) {
            return HostWrite.none( CodeHostOutcome.IGNORED );
        }
        final Optional< JdbcCodeHostHeads.Approval > before = heads.approvalOf( conn, merged.id(), f.headSha() );
        heads.recordApproval( conn, merged.id(), f.headSha(), reviewer, f.at() );
        final boolean changed = !heads.approvalOf( conn, merged.id(), f.headSha() ).equals( before );
        final boolean completes = f.headSha().equals( merged.headSha() )
            && "unreviewed".equals( merged.policyFlag() ) && merged.flagAcknowledgedAt() == null
            && merged.supersededBy() == null && merged.closedAt() != null && !f.at().isAfter( merged.closedAt() );
        if ( completes ) {
            rows.jdbc().update( conn, "UPDATE work_change SET merge_kind = 'reviewed', policy_flag = NULL, "
                + "reviewed_by = ?, reviewed_by_agent_id = NULL, external_reviewed_by = ?, reviewed_head_sha = head_sha, "
                + "reviewed_at = ? WHERE id = ?", ps -> {
                    ps.setString( 1, reviewer.login() );
                    ps.setString( 2, reviewer.login() == null ? reviewer.external() : null );
                    ps.setTimestamp( 3, JdbcCodeHostRows.ts( f.at() ) );
                    ps.setLong( 4, merged.id() );
                } );
            rows.event( conn, item, merged.id(), by, WorkEvent.CHANGE_REVIEWED, "approved", NOTE );
        } else if ( before.isEmpty() && changed ) {
            rows.event( conn, item, merged.id(), by, WorkEvent.CHANGE_REVIEWED, "approved", null );
        }
        return completes || changed ? new HostWrite( CodeHostOutcome.APPLIED, rows.byId( conn, merged.id() ) )
            : HostWrite.none( CodeHostOutcome.DUPLICATE );
    }
}
