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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;

/** Saved filters. Reached through {@link WorkStore#notifyStores()}; it has no visibility check. */
public interface WorkFilterStore {

    /**
     * @throws WorkConflictException reason {@code exists} when the owner already has a filter of that name
     */
    SavedFilter insert( String owner, String name, Long projectId, boolean shared,
                        SortedMap< String, String > filter ) throws SQLException;

    Optional< SavedFilter > byId( long id ) throws SQLException;

    /** How many filters {@code owner} has saved, of any scope. */
    int countOwned( String owner ) throws SQLException;

    /**
     * The owner's own filters that have no project or one of the given projects, and the shared filters of the given
     * projects, by name, at most {@code limit}. The projects are applied in the query, so the limit counts only rows
     * the caller may see.
     */
    List< SavedFilter > visibleTo( String owner, Collection< Long > projectIds, int limit ) throws SQLException;

    /**
     * @throws WorkConflictException reason {@code exists} when the owner already has a filter of that name
     * @throws WorkNotFoundException when there is no such filter
     */
    SavedFilter rename( long id, String name ) throws SQLException;

    /** Whether a row was removed. */
    boolean delete( long id ) throws SQLException;
}
