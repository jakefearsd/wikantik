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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.cache.CachingManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code key_facts} are cut from the body's first paragraphs when the page authors none, and the first paragraph of
 * a page that opens with access-rule directives is the directives plus the line after them. The projection is served
 * to anonymous agents, and a directive names people by login.
 */
class DefaultForAgentProjectionServiceAclDirectiveTest {

    @Test
    void bodyDerivedKeyFactsCarryNoAccessRuleDirectives() {
        final StructuralIndexService idx = mock( StructuralIndexService.class );
        final PageManager pm = mock( PageManager.class );
        final CachingManager cache = mock( CachingManager.class );
        when( cache.enabled( anyString() ) ).thenReturn( false );
        final DefaultForAgentProjectionService svc =
                new DefaultForAgentProjectionService( idx, pm, cache, new ForAgentMetrics(), null, null, null );

        final PageDescriptor d = new PageDescriptor( "01RULED", "RuledOps", "Ruled Ops", PageType.ARTICLE,
                "ops", List.of( "ops" ), "Operator notes.",
                Instant.parse( "2026-04-22T11:10:00Z" ), Optional.empty(), false );
        when( idx.getByCanonicalId( "01RULED" ) ).thenReturn( Optional.of( d ) );
        when( pm.getPureText( "RuledOps", -1 ) ).thenReturn(
                "---\ntitle: Ruled Ops\nsummary: Operator notes.\ntags: [ops]\n---\n"
                + "[{ALLOW view All}]\n[{ALLOW edit Admin,leak-probe@example.test}]\n"
                + "The service restarts nightly at 02:00 and retains 7 backups.\n\n## Steps\n\nRun the restart script.\n" );

        final ForAgentProjection p = svc.project( "01RULED" ).orElseThrow();

        assertFalse( p.keyFacts().isEmpty(), "the body still yields key facts" );
        p.keyFacts().forEach( f -> assertFalse( f.text().contains( "leak-probe@example.test" ), f.text() ) );
        assertEquals( "The service restarts nightly at 02:00 and retains 7 backups.", p.keyFacts().get( 0 ).text() );
        assertTrue( p.headingsOutline().stream().anyMatch( h -> "Steps".equals( h.text() ) ), "headings are untouched" );
    }
}
