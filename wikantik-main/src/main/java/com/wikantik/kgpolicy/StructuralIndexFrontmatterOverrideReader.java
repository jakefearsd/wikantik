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
package com.wikantik.kgpolicy;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;

import java.util.Optional;

/**
 * {@link FrontmatterOverrideReader} that resolves the {@code kg_include} value
 * from the structural index rather than re-parsing page frontmatter on every
 * policy evaluation. This avoids redundant I/O: the structural index already
 * holds a parsed, in-memory {@link PageDescriptor} for every indexed page.
 *
 * <p>Lookup is a two-step delegation:
 * <ol>
 *   <li>Resolve the page slug to a canonical ID via
 *       {@link StructuralIndexService#resolveCanonicalIdFromSlug(String)}.</li>
 *   <li>Fetch the {@link PageDescriptor} via
 *       {@link StructuralIndexService#getByCanonicalId(String)} and return its
 *       {@link PageDescriptor#kgInclude()} field.</li>
 * </ol>
 * If either lookup returns empty (page not indexed, or descriptor absent),
 * this reader returns {@link Optional#empty()} so that the policy falls back
 * to cluster / global defaults.</p>
 */
public class StructuralIndexFrontmatterOverrideReader implements FrontmatterOverrideReader {

    private final StructuralIndexService structural;

    public StructuralIndexFrontmatterOverrideReader( final StructuralIndexService structural ) {
        this.structural = structural;
    }

    @Override
    public Optional< Boolean > kgInclude( final String pageName ) {
        return structural.resolveCanonicalIdFromSlug( pageName )
                .flatMap( structural::getByCanonicalId )
                .flatMap( PageDescriptor::kgInclude );
    }
}
