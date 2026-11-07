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

/**
 * A saved page version. {@code body} is as stored (CRLF allowed); callers apply {@code CollabText.toLf}.
 * {@code contentHash} is {@link com.wikantik.collab.text.CollabText#contentHash} of {@code body} (SHA-256 hex of the
 * LF-normalised body; meta is compared separately). {@code author} is the author key that wrote this version, or
 * {@code null} when the author is unknown.
 */
public record PageState( int version, String body, Map< String, Object > meta, String contentHash, String author ) {
    public PageState {
        ModelChecks.nonNegative( version, "version" );
        Objects.requireNonNull( body, "body" );
        meta = ModelChecks.copyMeta( meta, "meta" );
        Objects.requireNonNull( contentHash, "contentHash" );
    }
}
