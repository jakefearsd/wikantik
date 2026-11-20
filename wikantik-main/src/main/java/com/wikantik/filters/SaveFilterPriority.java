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
package com.wikantik.filters;

/**
 * The one home for the priorities the engine registers its built-in page filters at.
 *
 * <p>Ordering rule ({@code PriorityList}): <strong>higher numbers run first</strong>. A filter
 * added at a priority equal to an existing one is placed <em>after</em> it, so ties run in
 * registration order. Execution order, first to last:</p>
 * <ol>
 *   <li>{@link #CITATION_LINK_RENDERING} (100)</li>
 *   <li>{@link #PAGE_OWNERSHIP} (-998)</li>
 *   <li>{@link #REFERENCES} (-1001)</li>
 *   <li>{@link #SEARCH_INDEX} (-1002)</li>
 *   <li>{@link #STRUCTURAL_SPINE} and {@link #RUNBOOK_VALIDATION} (both -1003; spine is registered
 *       first so it runs first)</li>
 *   <li>{@link #FRONTMATTER_DEFAULTS} (-1004)</li>
 *   <li>{@link #CHUNK_PROJECTOR} (-1005)</li>
 *   <li>{@link #SCHEMA_VALIDATION} (-1006)</li>
 *   <li>{@link #MATH_VALIDATION} (-1007)</li>
 * </ol>
 * Filters configured from XML register at 0, ahead of everything here except the first two.
 */
public final class SaveFilterPriority {

    public static final int CITATION_LINK_RENDERING = 100;
    public static final int PAGE_OWNERSHIP = -998;
    public static final int REFERENCES = -1001;
    public static final int SEARCH_INDEX = -1002;
    /** Ties with {@link #RUNBOOK_VALIDATION}; registration order decides (spine first). */
    public static final int STRUCTURAL_SPINE = -1003;
    public static final int RUNBOOK_VALIDATION = -1003;
    public static final int FRONTMATTER_DEFAULTS = -1004;
    public static final int CHUNK_PROJECTOR = -1005;
    public static final int SCHEMA_VALIDATION = -1006;
    public static final int MATH_VALIDATION = -1007;

    private SaveFilterPriority() {
    }
}
