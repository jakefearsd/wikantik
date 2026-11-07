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
package com.wikantik.collab.model;

import java.util.Map;
import java.util.Objects;

/** A request to save a page, conditional on {@code expectedVersion} still being the latest. */
public record PublishRequest( String pageName, String body, Map< String, Object > meta, int expectedVersion,
                              String authorKey, String changeNote ) {
    public PublishRequest {
        ModelChecks.nonBlank( pageName, "pageName" );
        Objects.requireNonNull( body, "body" );
        meta = ModelChecks.copyMeta( meta, "meta" );
        ModelChecks.nonNegative( expectedVersion, "expectedVersion" );
        ModelChecks.nonBlank( authorKey, "authorKey" );
        Objects.requireNonNull( changeNote, "changeNote" );
    }
}
