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
import com.wikantik.work.WorkCodeHostStore.BranchFacts;
import com.wikantik.work.WorkCodeHostStore.HostWrite;
import com.wikantik.work.WorkCodeHostStore.ReviewFacts;
import com.wikantik.work.WorkCodeHostStore.ReviewStep;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;


/**
 * The code host's branch pushes and pull-request steps, applied to the change records so that the result depends
 * on the host's times and never on the order deliveries arrive in. The rules, in one place:
 *
 * <ol>
 * <li><b>One pull request is one change</b> for its whole history: a pull request number of an item maps to at most
 * one change ({@code idx_work_change_review}). Closing it abandons that change; reopening revives the same row; there
 * is never a second change for it. A host that reuses the number of a merged pull request is a known limit: the
 * merged change keeps the number and the new pull request's events are recorded on it (they cannot reopen it).</li>
 * <li><b>Lifecycle state is a last-writer-wins register</b> over opened, reopened, closed and merged, ordered by the
 * host's time, then merged over closed over reopened over opened ({@link JdbcCodeHostLifecycle}). An event with an
 * older key than the one recorded changes no state; a newer one applies whatever arrived first. A merge is
 * terminal: a close or reopen stamped after it is logged at WARN and ignored. Only the code host's system actor
 * moves a change from abandoned back to open, and only through this store; agents and people still may not.</li>
 * <li><b>Everything else is monotone or keyed</b>: {@code opened_at} of a code-host change is the earliest host time
 * of any event recorded for it; {@code closed_at} is the winning close's or merge's time and null while the
 * register says open; heads go by host time, latest wins, ties by sha, and only the 200 most recent reports of a
 * change are kept ({@link JdbcCodeHostHeads}); an approval stays with the commit it approved; {@code first_commit_at}
 * is the earliest commit reported, never later than the opening and bounded by {@link CodeHostTimes}.</li>
 * <li><b>Precedence</b>: the code host never overwrites what an agent or a person reported: {@code opened_by*},
 * {@code head_agent_ids}, an agent's or a person's approval, a first commit typed by hand, and the opening time of a
 * change they opened. A new commit clears every approval, as an approval is of a commit.</li>
 * <li><b>The unreviewed flag</b> is decided when the merge is recorded, from the host's approval of the merged commit
 * by someone other than the author stamped no later than the merge (or an agent's or a person's approval standing on
 * it). An approval that arrives after the merge but was stamped no later than it, for the merged commit and not by
 * the author, withdraws an unacknowledged {@code unreviewed} flag in the same transaction as its event
 * ({@link JdbcCodeHostLateApprovals}); an acknowledged flag is never withdrawn, and an approval stamped after the
 * merge never withdraws one. The author's own approval is never recorded.</li>
 * <li><b>Every event has a host time.</b> The adapters take it from the event's own field; a delivery whose event has
 * no usable time is rejected before it gets here, and an event without one that still arrives is ignored, never
 * stamped with the time it was received. A time later than the work clock is the work clock ({@link CodeHostTimes}).</li>
 * <li><b>Idempotent</b>: applying any event again writes nothing. Each lifecycle event, each head commit and each
 * commit's first approval is written to the activity log once, the first time it is reported, whether or not it
 * changes the row, so the log holds the same rows whatever the order; the exception is a merge that arrives before
 * the approval that made it reviewed, whose log keeps the flag it raised and the withdrawal.</li>
 * </ol>
 *
 * <p>Every method is one transaction under the product's advisory lock, taking its locks in the order
 * {@link JdbcCodeHostRows} writes down. Nothing here moves an item's status. On a closed item nothing opens or
 * revives a change: an event that would is ignored when no change exists, and a reopen leaves the change abandoned.
 * The item's status is outside the order guarantee.
 */
final class JdbcWorkCodeHost {

    private final JdbcCodeHostRows rows;
    private final JdbcCodeHostHeads heads;
    private final JdbcCodeHostLifecycle lifecycle;
    private final JdbcCodeHostLateApprovals late;

    JdbcWorkCodeHost( final JdbcCodeHostRows rows ) {
        this.rows = rows;
        this.heads = new JdbcCodeHostHeads( rows );
        this.lifecycle = new JdbcCodeHostLifecycle( rows );
        this.late = new JdbcCodeHostLateApprovals( rows, heads );
    }

    HostWrite branchPush( final long productId, final Item item, final BranchFacts f, final Actor by )
            throws SQLException {
        return rows.locked( productId, by, conn -> {
            final List< Change > targets = rows.pushTargets( conn, item.id(), f.branch(), f.at() );
            final LockedItem locked = rows.lockItem( conn, item.id(), productId );
            if ( locked == null ) {
                return HostWrite.none( CodeHostOutcome.IGNORED );
            }
            if ( !targets.isEmpty() ) {
                boolean wrote = false;
                for ( final Change c : targets ) {
                    wrote |= touch( conn, item, c, f, by );
                }
                return wrote ? new HostWrite( CodeHostOutcome.APPLIED, rows.byId( conn, targets.get( 0 ).id() ) )
                    : HostWrite.none( CodeHostOutcome.DUPLICATE );
            }
            final Long seen = rows.seenOn( conn, item.id(), f.branch(), f.headSha() );
            if ( seen != null ) {
                boolean filled = rows.lowerOpened( conn, seen, f.at() );
                filled |= rows.lowerFirstCommit( conn, rows.byId( conn, seen ), f.firstCommitAt() );
                return filled ? new HostWrite( CodeHostOutcome.APPLIED, rows.byId( conn, seen ) )
                    : HostWrite.none( CodeHostOutcome.DUPLICATE );
            }
            if ( locked.closed() ) {
                return HostWrite.none( CodeHostOutcome.IGNORED );
            }
            final long id = rows.insert( conn, item, productId, new JdbcCodeHostRows.Fresh( f.branch(), f.baseBranch(),
                f.headSha(), null, null, f.firstCommitAt(), f.opener(), f.at() ), by );
            heads.record( conn, item, rows.byId( conn, id ), f.headSha(), f.at(), by );
            return new HostWrite( CodeHostOutcome.APPLIED, rows.byId( conn, id ) );
        } );
    }

    HostWrite review( final long productId, final Item item, final ReviewFacts f, final ReviewStep step,
                      final CodeHostWho reviewer, final Actor by ) throws SQLException {
        if ( step == ReviewStep.APPROVED && CodeHostFlags.isSelf( reviewer, f.author() ) ) {
            // the author's own approval is no review: it is recorded nowhere, in any order
            return HostWrite.none( CodeHostOutcome.IGNORED );
        }
        return rows.locked( productId, by, conn -> {
            final Change found = rows.lockedChange( conn, rows.prChangeId( conn, item.id(), f.number(), f.branch() ) );
            final LockedItem locked = rows.lockItem( conn, item.id(), productId );
            if ( locked == null ) {
                return HostWrite.none( CodeHostOutcome.IGNORED );
            }
            final HostWrite refused = found == null ? refuseOpening( conn, item, f, step, locked ) : null;
            if ( refused != null ) {
                return refused;
            }
            final Change c = found != null ? found : rows.byId( conn, rows.insert( conn, item, productId,
                new JdbcCodeHostRows.Fresh( f.branch(), f.baseBranch(), f.headSha(), f.number(), f.url(), null,
                    f.author(), f.at() ), by ) );
            final boolean wrote = found == null || rows.adopt( conn, c, f );
            if ( c.state() == ChangeState.MERGED && step == ReviewStep.APPROVED ) {
                rows.lowerOpened( conn, c.id(), f.at() );
                return late.approve( conn, item, rows.byId( conn, c.id() ), f, reviewer, by );
            }
            final CodeHostOutcome outcome = applyStep( conn, item, c.id(), f, step, reviewer, locked.closed(), by );
            if ( outcome == CodeHostOutcome.IGNORED && !wrote ) {
                return HostWrite.none( CodeHostOutcome.IGNORED );
            }
            return written( conn, wrote || outcome == CodeHostOutcome.APPLIED, c.id() );
        } );
    }

    /**
     * Why a pull-request event with no change opens none (null: it opens one): an agent's or a person's merge of the
     * branch already holds it, or the item is closed and the event is not a close.
     */
    private HostWrite refuseOpening( final Connection conn, final Item item, final ReviewFacts f, final ReviewStep step,
                                     final LockedItem locked ) throws SQLException {
        if ( rows.seenOnClosed( conn, item.id(), f.branch(), f.headSha(), f.number() ) != null ) {
            return HostWrite.none( CodeHostOutcome.DUPLICATE );
        }
        return locked.closed() && step != ReviewStep.CLOSED ? HostWrite.none( CodeHostOutcome.IGNORED ) : null;
    }

    private HostWrite written( final Connection conn, final boolean wrote, final long id ) throws SQLException {
        return wrote ? new HostWrite( CodeHostOutcome.APPLIED, rows.byId( conn, id ) )
            : HostWrite.none( CodeHostOutcome.DUPLICATE );
    }

    /**
     * One pull-request step on its change: the opening time, the head report (every step but an approval, which
     * names the commit it approved, maybe an old one), then the lifecycle register or what the step itself says.
     */
    private CodeHostOutcome applyStep( final Connection conn, final Item item, final long id, final ReviewFacts f,
                                       final ReviewStep step, final CodeHostWho reviewer, final boolean itemClosed,
                                       final Actor by ) throws SQLException {
        final JdbcCodeHostLifecycle.Kind kind = lifecycleKind( step );
        if ( isAfterMerge( rows.byId( conn, id ), f ) ) {
            // stamped after the merge: it records nothing (a close or reopen is logged at WARN and counted ignored)
            return kind == JdbcCodeHostLifecycle.Kind.CLOSED || kind == JdbcCodeHostLifecycle.Kind.REOPENED
                ? lifecycle.apply( conn, item, rows.byId( conn, id ), kind, f.at(), itemClosed, by )
                : CodeHostOutcome.DUPLICATE;
        }
        boolean wrote = rows.lowerOpened( conn, id, f.at() );
        if ( step != ReviewStep.APPROVED ) {
            wrote |= heads.observe( conn, item, rows.byId( conn, id ), f.headSha(), f.at(), by );
        }
        if ( kind != null ) {
            wrote |= lifecycle.apply( conn, item, rows.byId( conn, id ), kind, f.at(), itemClosed, by )
                == CodeHostOutcome.APPLIED;
        }
        final Change now = rows.byId( conn, id );
        wrote |= switch ( step ) {
            case OPENED, READY -> lifecycle.requestReview( conn, item, now, by );
            case APPROVED -> heads.approve( conn, item, now, f.headSha(), reviewer, f.at(), by );
            default -> false;
        };
        return wrote ? CodeHostOutcome.APPLIED : CodeHostOutcome.DUPLICATE;
    }

    /** Whether the change is merged and the event was stamped after its merge. */
    private static boolean isAfterMerge( final Change c, final ReviewFacts f ) {
        return c.state() == ChangeState.MERGED && c.closedAt() != null && f.at().isAfter( c.closedAt() );
    }

    private static JdbcCodeHostLifecycle.Kind lifecycleKind( final ReviewStep step ) {
        return switch ( step ) {
            case OPENED, OPENED_DRAFT -> JdbcCodeHostLifecycle.Kind.OPENED;
            case REOPENED -> JdbcCodeHostLifecycle.Kind.REOPENED;
            case CLOSED -> JdbcCodeHostLifecycle.Kind.CLOSED;
            default -> null;
        };
    }

    /** A push to a branch one of the item's changes follows: the opening time, the head report, the first commit. */
    private boolean touch( final Connection conn, final Item item, final Change target, final BranchFacts f,
                           final Actor by ) throws SQLException {
        boolean wrote = rows.lowerOpened( conn, target.id(), f.at() );
        wrote |= heads.observe( conn, item, rows.byId( conn, target.id() ), f.headSha(), f.at(), by );
        return rows.lowerFirstCommit( conn, rows.byId( conn, target.id() ), f.firstCommitAt() ) || wrote;
    }
}
