/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge.testfakes;

import com.wikantik.api.search.SearchResult;
import com.wikantik.event.WikiEvent;
import com.wikantik.search.SearchManager;
import com.wikantik.search.SearchProvider;

import java.util.*;

/**
 * Minimal SearchManager fake for unit tests.
 * The only abstract method on SearchManager itself is getSearchEngine();
 * all PageFilter and WikiEventListener methods have default implementations.
 * We override findPages() directly (it has a default but calls getSearchEngine),
 * so tests can inject results without a real search provider.
 */
public class FakeSearchManager implements SearchManager {

    private List< SearchResult > nextResults = List.of();

    public void setResults( final List< SearchResult > results ) {
        this.nextResults = List.copyOf( results );
    }

    @Override
    public Collection< SearchResult > findPages( final String query,
            final com.wikantik.api.core.Context ctx ) {
        return nextResults;
    }

    @Override
    public SearchProvider getSearchEngine() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        throw new UnsupportedOperationException();
    }
}
