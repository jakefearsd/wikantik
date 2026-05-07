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
package com.wikantik.search.subsystem.lucene;

/**
 * Read-side seam for {@code LuceneSearchProvider}.
 *
 * <p>Phase 7 Checkpoint 1 placeholder. Phase 7 Checkpoint 3 splits
 * {@code LuceneSearchProvider} into write/read/lifecycle collaborators;
 * this interface will then carry {@code findPages} overloads,
 * {@code moreLikeThis}, query parsing, and snippet/highlight helpers. Until
 * then the slot on
 * {@link com.wikantik.search.subsystem.SearchSubsystem.Services#luceneSearcher()}
 * stays {@code null}.</p>
 */
public interface LuceneSearcher {
}
