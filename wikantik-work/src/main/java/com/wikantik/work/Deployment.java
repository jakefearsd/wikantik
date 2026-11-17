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
import java.util.List;

/**
 * One product version going to an environment, carrying a set of items. People are logins, agents are ids.
 * {@code itemIds} is everything it carries, whoever is looking: a caller is shown only the items it can see.
 */
public record Deployment( long id, long productId, String environment, String version, DeploymentState state,
                          String requestedBy, Long requestedByAgentId, String approvedBy, Long approvedByAgentId,
                          Instant requestedAt, Instant approvedAt, Instant startedAt, Instant verifiedAt,
                          Instant closedAt, String evidence, List< Long > itemIds ) {

    public Deployment {
        itemIds = itemIds == null ? List.of() : List.copyOf( itemIds );
    }
}
