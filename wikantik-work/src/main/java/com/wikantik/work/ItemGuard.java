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

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A check made inside the transaction that creates an item, before anything is inserted: it may take a lock and
 * refuse by throwing, so the decision and the insert cannot be separated by a concurrent request.
 */
@FunctionalInterface
public interface ItemGuard {

    /** Checks nothing. */
    ItemGuard NONE = conn -> { };

    /** Runs first in the creating transaction; a thrown exception rolls the creation back. */
    void check( Connection conn ) throws SQLException;
}
