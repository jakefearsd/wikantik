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
import java.util.Optional;

/** A project's default product, reached through {@link WorkStore#notifyStores()}; stateless. */
public interface WorkProjectDefaultsStore {

    /** The product with this id, archived ones included. */
    Optional< Product > product( long id ) throws SQLException;

    /**
     * Makes {@code productOrNull} (null clears it) the project's default product and records one {@code settings}
     * event (note {@code default_product}, product keys in from/to), in one transaction. Unchanged writes nothing.
     */
    void setDefaultProduct( long projectId, Product productOrNull, Actor by ) throws SQLException;
}
