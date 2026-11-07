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

import com.wikantik.collab.text.ChangeSet;

import java.util.Map;
import java.util.Objects;

/**
 * A checkpoint of a session at {@code version}/{@code metaSeq}; {@code local} is the unpublished change set, relative to
 * the page version {@code basePageVersion} the session was based on when the snapshot was taken.
 */
public record Snapshot( int version, long metaSeq, int basePageVersion, String body, Map< String, Object > meta,
                        ChangeSet local ) {
    public Snapshot {
        ModelChecks.nonNegative( version, "version" );
        ModelChecks.nonNegative( metaSeq, "metaSeq" );
        ModelChecks.nonNegative( basePageVersion, "basePageVersion" );
        Objects.requireNonNull( body, "body" );
        meta = ModelChecks.copyMeta( meta, "meta" );
        Objects.requireNonNull( local, "local" );
    }
}
