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
package com.wikantik.extractcli;

/**
 *  The taxonomy-bearing frontmatter of one page — the only fields a corpus divergence
 *  check needs to compare.
 *
 *  <p>Deliberately not the page body: bodies differ constantly and legitimately (an editor
 *  saved a typo fix), whereas identity and taxonomy drifting apart between the repository
 *  and production is what silently invalidates a corpus-wide plan.</p>
 *
 *  @param slug        the page name
 *  @param canonicalId the rename-stable ULID, or {@code null} when unassigned
 *  @param cluster     the declared cluster path, or {@code null}
 *  @param type        the frontmatter {@code type}, or {@code null}
 */
public record PageFacts( String slug, String canonicalId, String cluster, String type ) {
}
