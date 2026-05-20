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
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Mockito-backed stub for {@link MentionIndex}. Bypasses the DataSource-required constructor. */
public final class FakeMentionIndex {

    /**
     * Returns a {@link MentionIndex} mock that yields {@code matches} whenever
     * {@code findRelatedPages(pageName, _)} is called, and empty for any other
     * page name. Also stubs {@code findRelatedPagesBatch} so the batched
     * call-site in {@link com.wikantik.knowledge.DefaultContextRetrievalService}
     * sees the same data.
     */
    public static MentionIndex relating( final String pageName,
                                          final List< MentionIndex.RelatedByMention > matches ) {
        final MentionIndex mocked = mock( MentionIndex.class );
        when( mocked.findRelatedPages( eq( pageName ), anyInt() ) ).thenReturn( matches );
        // The batch call needs to seed the same data keyed by the input name.
        // We use an Answer rather than a fixed map so the stub responds correctly
        // regardless of which subset of names the consumer passes in.
        when( mocked.findRelatedPagesBatch( anyList(), anyInt() ) ).thenAnswer( inv -> {
            final List< String > names = inv.getArgument( 0 );
            final java.util.Map< String, List< MentionIndex.RelatedByMention > > out =
                new java.util.LinkedHashMap<>();
            for ( final String n : names ) {
                out.put( n, pageName.equals( n ) ? matches : List.of() );
            }
            return Map.copyOf( out );
        } );
        return mocked;
    }

    private FakeMentionIndex() {}
}
