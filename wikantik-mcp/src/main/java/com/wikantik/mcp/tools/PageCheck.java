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
package com.wikantik.mcp.tools;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for page validation checks (Gang of Four: Strategy pattern).
 *
 * <p>Each implementation encapsulates a single validation concern — SEO readiness,
 * metadata completeness, structural integrity, etc.  Tools like {@link AuditClusterTool}
 * and {@link VerifyPagesTool} compose the checks they need, avoiding duplicated
 * validation logic.
 *
 * <p>Implementations must be stateless and thread-safe.  All mutable state lives in the
 * {@link PageCheckContext} passed to {@link #check}.
 *
 * <h3>Adding a new check</h3>
 * <ol>
 *   <li>Create a class that implements {@code PageCheck}</li>
 *   <li>Return results via {@link PageCheckResult} with appropriate severity and category</li>
 *   <li>Add the check to the relevant tool's check list</li>
 *   <li>Write a unit test for the check in isolation</li>
 * </ol>
 *
 * @see PageCheckResult
 * @see PageCheckContext
 * @see PageChecks
 */
public interface PageCheck {

    /**
     * Runs this check against a single page.
     *
     * @param context everything the check needs: page name, metadata, body, etc.
     * @return a list of findings (empty if the page passes this check)
     */
    List< PageCheckResult > check( PageCheckContext context );
}
