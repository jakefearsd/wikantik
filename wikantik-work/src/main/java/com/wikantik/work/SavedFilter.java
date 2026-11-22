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

import java.time.Instant;
import java.util.SortedMap;

/**
 * A person's named search. {@code owner} is the login that saved it and {@code filter} the search as the wire
 * parameters ({@link FindParams}); both hold logins, never display names, so a view for a reader must map them
 * ("logins; map before showing"). A shared filter carries the project it is pinned to; a private one may carry none.
 */
public record SavedFilter( long id, String owner, String name, Long projectId, String projectKey, boolean shared,
                           SortedMap< String, String > filter, Instant createdAt ) {
}
