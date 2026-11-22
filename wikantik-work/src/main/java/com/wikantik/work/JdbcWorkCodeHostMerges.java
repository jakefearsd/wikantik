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

import com.wikantik.work.JdbcCodeHostRows.LockedItem;
import com.wikantik.work.WorkCodeHostStore.DirectFacts;
import com.wikantik.work.WorkCodeHostStore.HostWrite;
import com.wikantik.work.WorkCodeHostStore.ReviewFacts;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Merges and direct pushes the code host reports. A pull request's merge and the push that carries it onto the default
 * branch are two reports of one fact, and either may arrive first: the push is recognised by the merge commit
 * ({@code merged_sha}), the pull request's head or its number, and a direct change recorded before the merge is
 * marked superseded (never counted twice). A report never overwrites what an agent or a person recorded; a merge
 * already merged is a duplicate. Nothing here moves an item's status.
 */
final class JdbcWorkCodeHostMerges {

    private final JdbcCodeHostRows rows;
    private final JdbcCodeHostHeads heads;
    private final JdbcCodeHostLifecycle lifecycle;

    JdbcWorkCodeHostMerges( final JdbcCodeHostRows rows ) {
        this.rows = rows;
        this.heads = new JdbcCodeHostHeads( rows );
        this.lifecycle = new JdbcCodeHostLifecycle( rows );
    }

    /**
     * Records the pull request's merge on its one change (open, in review or abandoned: a merge ends it whatever
     * came before; one already merged is a duplicate). The rows it may write (that change, and a direct change this
     * merge's own push recorded first) are locked before anything is decided, and "already merged" is decided under
     * those locks: a merge an agent or a person is committing at the same moment is waited for and then found, never
     * recorded a second time.
     */
    HostWrite merge( final long productId, final Item item, final ReviewFacts f, final String mergedSha,
                     final CodeHostWho merger, final EffectivePolicy policy, final Actor by ) throws SQLException {
        return rows.locked( productId, by, conn -> {
            final Long changeId = rows.prChangeId( conn, item.id(), f.number(), f.branch() );
            final Long directId = directId( conn, item.id(), mergedSha );
            rows.lockInIdOrder( conn, changeId, directId );
            Change c = changeId == null ? null : rows.byId( conn, changeId );
            if ( c != null && c.state() == ChangeState.MERGED || alreadyMerged( conn, item.id(), f, mergedSha ) ) {
                return HostWrite.none( CodeHostOutcome.DUPLICATE );
            }
            if ( rows.lockItem( conn, item.id(), productId ) == null ) {
                return HostWrite.none( CodeHostOutcome.IGNORED );
            }
            if ( c == null ) {
                // a merge of a pull request whose opening was never seen: it is merged, so it holds nothing
                c = rows.byId( conn, rows.insert( conn, item, productId, new JdbcCodeHostRows.Fresh( f.branch(),
                    f.baseBranch(), f.headSha(), f.number(), f.url(), null, f.author(), f.at() ), by ) );
                heads.record( conn, item, c, f.headSha(), f.at(), by );
            } else {
                rows.adopt( conn, c, f );
                rows.lowerOpened( conn, c.id(), f.at() );
            }
            final Change merged = settle( conn, item, rows.byId( conn, c.id() ), f, mergedSha, merger, policy, by );
            supersedeDirect( conn, item, merged, directId, by );
            return new HostWrite( CodeHostOutcome.APPLIED, rows.byId( conn, merged.id() ) );
        } );
    }

    /** The code-host direct change this merge's push recorded, not yet superseded; null when none. */
    private Long directId( final Connection conn, final long itemId, final String mergedSha ) throws SQLException {
        return rows.jdbc().queryOne( conn, "SELECT id FROM work_change WHERE item_id = ? AND merge_kind = 'direct' "
                + "AND source = 'codehost' AND superseded_by IS NULL AND merged_sha = ? ORDER BY id LIMIT 1", ps -> {
                    ps.setLong( 1, itemId );
                    ps.setString( 2, mergedSha );
                }, rs -> rs.getLong( 1 ) ).orElse( null );
    }

    /**
     * The merge itself, decided on the locked row: the merged commit becomes the head, the kind is judged from the
     * approvals of that commit no later than the merge (rule E), then the state, the register and the events.
     */
    private Change settle( final Connection conn, final Item item, final Change locked, final ReviewFacts f,
                           final String mergedSha, final CodeHostWho merger, final EffectivePolicy policy,
                           final Actor by ) throws SQLException {
        heads.observe( conn, item, locked, f.headSha(), f.at(), by );
        Change c = rows.byId( conn, locked.id() );
        if ( f.headSha() != null && !f.headSha().equals( c.headSha() ) ) {
            // the head that was merged is the change's last head, whatever the host's view of the branch says
            heads.moveTo( conn, item, c, f.headSha(), by );
            c = rows.byId( conn, c.id() );
        }
        final Optional< JdbcCodeHostHeads.Approval > host = heads.approvalOf( conn, c.id(), c.headSha() );
        if ( JdbcCodeHostHeads.hostStanding( c, host ) && host.get().at().isAfter( f.at() ) ) {
            // stamped after the merge: it is not what the merge was judged on, whichever arrived first
            heads.clearApproval( conn, c.id() );
            c = rows.byId( conn, c.id() );
        }
        final MergeKind kind = CodeHostFlags.kind( c, f, host );
        final String flag = CodeHostFlags.forMerge( kind, policy );
        final long id = c.id();
        lifecycle.merged( conn, id, f.at() );
        rows.jdbc().update( conn, "UPDATE work_change SET state = ?, merged_sha = ?, merge_kind = ?, merged_by = ?, "
                + "external_merged_by = ?, closed_at = GREATEST( ?, opened_at ), policy_flag = ? WHERE id = ?", ps -> {
                    ps.setString( 1, ChangeState.MERGED.wire() );
                    ps.setString( 2, mergedSha );
                    ps.setString( 3, kind.wire() );
                    ps.setString( 4, merger.login() );
                    ps.setString( 5, merger.login() == null ? merger.external() : null );
                    ps.setTimestamp( 6, JdbcCodeHostRows.ts( f.at() ) );
                    ps.setString( 7, flag );
                    ps.setLong( 8, id );
                } );
        rows.event( conn, item, id, by, WorkEvent.CHANGE_MERGED, kind.wire(), mergedSha );
        if ( flag != null ) {
            rows.event( conn, item, id, by, WorkEvent.CHANGE_FLAGGED, flag, null );
        }
        return rows.byId( conn, id );
    }

    /**
     * Whether the merge is already on record on another change: its branch merged with this commit by an agent or a
     * person (a change no other pull request is linked to).
     */
    private boolean alreadyMerged( final Connection conn, final long itemId, final ReviewFacts f,
                                   final String mergedSha ) throws SQLException {
        return rows.jdbc().queryOne( conn, "SELECT 1 FROM work_change WHERE item_id = ? AND state = 'merged' "
                + "AND branch = ? AND ( merged_sha = ? OR head_sha = ? ) "
                + "AND ( review_number IS NULL OR review_number = ? ) LIMIT 1", ps -> {
                    ps.setLong( 1, itemId );
                    ps.setString( 2, f.branch() );
                    ps.setString( 3, mergedSha );
                    ps.setString( 4, f.headSha() );
                    ps.setString( 5, f.number() );
                }, rs -> Boolean.TRUE ).isPresent();
    }

    /** A direct change recorded from this merge's own push (P5-2): mark it superseded and keep the earlier first commit. */
    private void supersedeDirect( final Connection conn, final Item item, final Change merged, final Long directId,
                                  final Actor by ) throws SQLException {
        if ( directId == null || directId == merged.id() ) {
            return;
        }
        final Change d = rows.byId( conn, directId );
        if ( d.supersededBy() != null ) {
            return;
        }
        rows.jdbc().update( conn, "UPDATE work_change SET superseded_by = ? WHERE id = ?", ps -> {
            ps.setLong( 1, merged.id() );
            ps.setLong( 2, d.id() );
        } );
        final Instant earlier = JdbcCodeHostRows.earlier( merged.firstCommitAt(), d.firstCommitAt(),
            JdbcCodeHostRows.cap( merged ) );
        if ( earlier != null && !earlier.equals( merged.firstCommitAt() ) ) {
            rows.setFirstCommit( conn, merged.id(), earlier );
        }
        rows.event( conn, item, d.id(), by, WorkEvent.CHANGE_SUPERSEDED, "change:" + merged.id(), null );
    }

    HostWrite direct( final long productId, final Item item, final DirectFacts f, final String flag, final Actor by )
            throws SQLException {
        return rows.locked( productId, by, conn -> {
            final LockedItem locked = rows.lockItem( conn, item.id(), productId );
            if ( locked == null ) {
                return HostWrite.none( CodeHostOutcome.IGNORED );
            }
            if ( known( conn, item.id(), f ) ) {
                return HostWrite.none( CodeHostOutcome.DUPLICATE );
            }
            final Optional< Long > made = insertDirect( conn, item, productId, f, flag );
            if ( made.isEmpty() ) {
                return HostWrite.none( CodeHostOutcome.DUPLICATE );
            }
            rows.event( conn, item, made.get(), by, WorkEvent.CHANGE_MERGED, MergeKind.DIRECT.wire(), f.after() );
            if ( flag != null ) {
                rows.event( conn, item, made.get(), by, WorkEvent.CHANGE_FLAGGED, flag, null );
            }
            return new HostWrite( CodeHostOutcome.APPLIED, rows.byId( conn, made.get() ) );
        } );
    }

    private Optional< Long > insertDirect( final Connection conn, final Item item, final long productId,
                                           final DirectFacts f, final String flag ) throws SQLException {
        final CodeHostWho who = f.author() == null ? CodeHostWho.UNKNOWN : f.author();
        return rows.jdbc().queryOne( conn, """
            INSERT INTO work_change (item_id, product_id, branch, base_branch, head_sha, state, merged_sha,
                merge_kind, opened_by, external_opened_by, merged_by, external_merged_by, source, policy_flag,
                first_commit_at, closed_at, opened_at)
            VALUES (?,?,?,?,?, 'merged', ?, 'direct', ?, ?, ?, ?, 'codehost', ?, ?, ?, ?)
            ON CONFLICT (item_id, merged_sha) WHERE merge_kind = 'direct' AND source = 'codehost' DO NOTHING
            RETURNING id
            """, ps -> {
                ps.setLong( 1, item.id() );
                ps.setLong( 2, productId );
                ps.setString( 3, f.branch() );
                ps.setString( 4, f.branch() );
                ps.setString( 5, f.headSha() );
                ps.setString( 6, f.after() );
                ps.setString( 7, who.login() );
                ps.setString( 8, who.login() == null ? who.external() : null );
                ps.setString( 9, who.login() );
                ps.setString( 10, who.login() == null ? who.external() : null );
                ps.setString( 11, flag );
                final Instant first = f.firstCommitAt() == null ? null
                    : f.firstCommitAt().isAfter( f.at() ) ? f.at() : f.firstCommitAt();
                ps.setTimestamp( 12, first == null ? null : JdbcCodeHostRows.ts( first ) );
                ps.setTimestamp( 13, JdbcCodeHostRows.ts( f.at() ) );
                ps.setTimestamp( 14, JdbcCodeHostRows.ts( f.at() ) );
            }, rs -> rs.getLong( 1 ) );
    }

    /** Whether the push is already on record: its commit, any commit of it as a head or merge, or its pull request. */
    private boolean known( final Connection conn, final long itemId, final DirectFacts f ) throws SQLException {
        final String[] shas = f.pushShas().toArray( new String[ 0 ] );
        final String[] numbers = f.reviewNumbers().toArray( new String[ 0 ] );
        return rows.jdbc().queryOne( conn, "SELECT 1 FROM work_change WHERE item_id = ? AND ( merged_sha = ? "
                + "OR merged_sha = ANY( ?::text[] ) OR head_sha = ANY( ?::text[] ) "
                + "OR review_number = ANY( ?::text[] ) ) LIMIT 1", ps -> {
                    ps.setLong( 1, itemId );
                    ps.setString( 2, f.after() );
                    ps.setArray( 3, ps.getConnection().createArrayOf( "text", shas ) );
                    ps.setArray( 4, ps.getConnection().createArrayOf( "text", shas ) );
                    ps.setArray( 5, ps.getConnection().createArrayOf( "text", numbers ) );
                }, rs -> Boolean.TRUE ).isPresent();
    }
}
