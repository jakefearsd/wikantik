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

import java.sql.SQLException;
import java.util.Set;

/** Who watches an item. Reached through {@link WorkStore#notifyStores()}. */
public interface WorkWatchStore {

    /** Starts watching unless the person already has a row, so a mute stays. */
    void watch( long itemId, String login ) throws SQLException;

    /** Starts watching whatever the person had before, overriding a mute. */
    void subscribe( long itemId, String login ) throws SQLException;

    /** Mutes the item for the person. */
    void mute( long itemId, String login ) throws SQLException;

    /** Removes the person's row. */
    void unwatch( long itemId, String login ) throws SQLException;

    /** The person's state; {@link WatchState#NONE} without a row. */
    WatchState stateOf( long itemId, String login ) throws SQLException;

    /** Logins whose state is {@code watching} (not the muted). */
    Set< String > watchers( long itemId ) throws SQLException;
}
