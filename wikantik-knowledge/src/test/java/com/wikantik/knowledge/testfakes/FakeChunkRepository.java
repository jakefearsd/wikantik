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

import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository.MentionableChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** In-memory {@link ContentChunkRepository} for unit tests. */
public class FakeChunkRepository extends ContentChunkRepository {

    private final Map< UUID, MentionableChunk > byId = new LinkedHashMap<>();

    public FakeChunkRepository() {
        super( null );  // DataSource not used by overridden methods
    }

    public void addChunk( UUID id, String page, int idx, List< String > headingPath, String text ) {
        byId.put( id, new MentionableChunk( id, page, idx, headingPath, text ) );
    }

    @Override public List< MentionableChunk > findByIds( List< UUID > ids ) {
        if ( ids == null || ids.isEmpty() ) return List.of();
        final List< MentionableChunk > out = new ArrayList<>();
        for ( final UUID id : ids ) {
            final MentionableChunk mc = byId.get( id );
            if ( mc != null ) out.add( mc );
        }
        return out;
    }
}
