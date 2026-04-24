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

import com.wikantik.api.core.Page;
import com.wikantik.api.search.SearchResult;

import static org.mockito.Mockito.*;

public final class FakeSearchResult implements SearchResult {

    public static FakeSearchResult of( String name, int score ) {
        return new FakeSearchResult( name, score );
    }

    private final String name;
    private final int score;
    private final Page page;

    private FakeSearchResult( String name, int score ) {
        this.name = name;
        this.score = score;
        this.page = mock( Page.class );
        when( this.page.getName() ).thenReturn( name );
        when( this.page.getAuthor() ).thenReturn( "test-author" );
    }

    @Override public Page getPage() { return page; }
    @Override public int getScore() { return score; }
    @Override public String[] getContexts() { return new String[ 0 ]; }

    public String name() { return name; }
}
