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
package com.wikantik.citation;

import com.wikantik.api.citation.CitationStatus;
import java.time.Instant;

/** A persisted citation row. id<=0 and null timestamps for not-yet-inserted rows. */
public record CitationRow(
        long id,
        String sourceCanonicalId,
        String targetCanonicalId,
        String targetHeadingPath,
        String spanText,
        String spanHash,
        String claimText,
        int ordinal,
        Integer pinnedTargetVersion,
        CitationStatus status,
        Instant firstSeen,
        Instant lastChecked,
        Instant lastStatusChange ) {

    /** Identity key (excludes id) used to match a re-parsed citation to an existing row. */
    public String identity() {
        return sourceCanonicalId + " " + targetCanonicalId + " "
             + targetHeadingPath + " " + spanHash + " " + ordinal;
    }
}
