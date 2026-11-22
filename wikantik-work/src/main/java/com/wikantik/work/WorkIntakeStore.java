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
import java.util.List;
import java.util.Set;

/**
 * The reads intake needs, reached through {@link WorkStore#notifyStores()}; stateless. The rows these read are
 * the reporter's: nothing here is a visibility decision, and only {@code WorkStanding} (and the intake service for
 * the items a person reported) may call them.
 */
public interface WorkIntakeStore {

    /**
     * The projects (not archived) a person may file into: any {@code user} row of theirs or {@code group} row of one of
     * their groups, whatever its role (reporter included); every project for a wiki admin. Empty for any actor that is
     * not a person, without a query.
     */
    List< Project > reportableProjects( Actor actor ) throws SQLException;

    /**
     * The items a person reported themselves (never one an agent of the same name reported) in the given projects,
     * most recently updated first, at most {@code limit}. No visibility decision: the caller chooses the projects.
     */
    List< Item > reportedBy( String login, Set< Long > projectIds, int limit ) throws SQLException;

    /**
     * Creates the item as {@code by} like {@link WorkStore#createItem}, but first, in the same transaction, takes a
     * transaction-scoped advisory lock on the person (so two filings by one person queue), counts the items they filed
     * in the last 24 hours by the database clock, in every project (never an agent of the same name), and throws
     * {@link WorkConflictException#INTAKE_LIMIT} when the count has reached {@code cap}. Count and insert are therefore
     * atomic per person.
     */
    Item createWithinDailyCap( Project project, Item draft, Actor by, int cap ) throws SQLException;

    /** Whether the project holds any reporter row: the outside-reporter read exits on this before touching anything else. */
    boolean hasReporters( long projectId ) throws SQLException;
}
