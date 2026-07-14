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
package com.wikantik.connectors.github;

import java.io.IOException;
import java.util.Optional;

/** Injectable GitHub REST seam — faked in unit tests, HTTP-implemented by HttpGithubApi. */
public interface GithubApi {
    /** The repository's default branch name. */
    String defaultBranch() throws IOException;
    /** Recursive tree listing (blobs only) of the given branch. */
    TreeListing listTree( String branch ) throws IOException;
    /** Raw file content; {@code Optional.empty()} on 404 (deleted between listing and fetch —
     *  authoritative absence, the connector skips without tainting the batch). */
    Optional< byte[] > rawContent( String path, String branch ) throws IOException;
}
