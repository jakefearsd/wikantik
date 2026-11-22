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

/**
 * When a flagged merge still waits for a lead, and so when a {@code change_flagged} notice is still worth reading: the
 * one rule for the flagged lane ({@link #waiting}) and for the notices ({@link #liveNoticeSql}).
 *
 * <p>A flag waits while the change has a {@code policy_flag}, no lead has acknowledged it and it is not superseded. A
 * later delivery can end that: a late approval clears the flag, a pull request's merge supersedes a direct change, and
 * one lead's acknowledgement answers it for every lead. Nothing is retracted or deleted; instead the inbox, the unread
 * count, mark-all-read and the email digest all read a {@code change_flagged} notice only while its change still
 * waits, so the answer is right whichever of the dispatch and the clearing delivery happened first.
 */
final class FlagNotices {

    private FlagNotices() {
    }

    /** The SQL condition that the {@code work_change} row aliased {@code alias} is a flag still waiting for a lead. */
    static String waiting( final String alias ) {
        final String c = alias + ".";
        return c + "policy_flag IS NOT NULL AND " + c + "flag_acknowledged_at IS NULL AND " + c + "superseded_by IS NULL";
    }

    /**
     * The SQL condition, over the {@code work_notification} row aliased {@code notice}, that it is live: any notice
     * that is not about a {@code change_flagged} event, and a {@code change_flagged} notice whose change (named by the
     * event's {@code from_value} {@code change:<id>}) still {@link #waiting waits}.
     */
    static String liveNoticeSql( final String notice ) {
        return "NOT EXISTS ( SELECT 1 FROM work_event fe WHERE fe.id = " + notice + ".event_id AND fe.type = '"
            + WorkEvent.CHANGE_FLAGGED + "' AND NOT EXISTS ( SELECT 1 FROM work_change fc WHERE fc.id = CASE WHEN "
            + "fe.from_value ~ '^change:[0-9]{1,18}$' THEN substr( fe.from_value, 8 )::bigint END AND "
            + waiting( "fc" ) + " ) )";
    }
}
