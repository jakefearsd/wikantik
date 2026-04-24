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
package com.wikantik.knowledge.testfakes;

import com.wikantik.knowledge.MentionIndex;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Mockito-backed stub for {@link MentionIndex}. Bypasses the DataSource-required constructor. */
public final class FakeMentionIndex {

    /**
     * Returns a {@link MentionIndex} mock that yields {@code matches} whenever
     * {@code findRelatedPages(pageName, _)} is called, and empty for any other
     * page name.
     */
    public static MentionIndex relating( final String pageName,
                                          final List< MentionIndex.RelatedByMention > matches ) {
        final MentionIndex mocked = mock( MentionIndex.class );
        when( mocked.findRelatedPages( eq( pageName ), anyInt() ) ).thenReturn( matches );
        return mocked;
    }

    private FakeMentionIndex() {}
}
