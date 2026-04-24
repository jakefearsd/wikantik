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
package com.wikantik.knowledge.test;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.JdbcKnowledgeRepository;

import java.util.*;

/**
 * Test fake that stores the written markdown in an {@link InMemoryPageManager} and
 * projects frontmatter directly into {@link JdbcKnowledgeRepository}, mirroring
 * production's save-time behaviour (without the retired GraphProjector projection).
 * Lets accept-path tests verify both the written page and the resulting
 * kg_nodes/kg_edges rows without spinning up a full engine.
 */
public class InMemoryPageSaveHelper {

    private final InMemoryPageManager pages;
    private final JdbcKnowledgeRepository kgRepo;

    public InMemoryPageSaveHelper( final InMemoryPageManager pages,
                                    final JdbcKnowledgeRepository kgRepo ) {
        this.pages = pages;
        this.kgRepo = kgRepo;
    }

    public void saveText( final String pageName, final String text ) {
        pages.putText( pageName, text );
        final ParsedPage parsed = FrontmatterParser.parse( text );
        final Map< String, Object > fm = parsed.metadata();
        if ( fm == null || fm.isEmpty() ) return;
        final String type = String.valueOf( fm.getOrDefault( "type", "article" ) );
        final Map< String, Object > props = new LinkedHashMap<>();
        for ( final var e : fm.entrySet() ) {
            if ( !( e.getValue() instanceof List ) ) props.put( e.getKey(), e.getValue() );
        }
        final var node = kgRepo.upsertNode( pageName, type, pageName, Provenance.HUMAN_AUTHORED, props );
        final Object related = fm.get( "related" );
        if ( related instanceof List< ? > list ) {
            for ( final Object raw : list ) {
                final String target = String.valueOf( raw );
                final var targetNode = kgRepo.upsertNode( target, "article", target,
                    Provenance.HUMAN_AUTHORED, Map.of() );
                kgRepo.upsertEdge( node.id(), targetNode.id(), "related",
                    Provenance.HUMAN_AUTHORED, Map.of() );
            }
        }
    }
}
