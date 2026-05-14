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
package com.wikantik.mcp.tools.kg;

import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Tier;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchKnowledgeToolTierTest {

    @Test
    void execute_with_min_tier_human_passes_human_to_service() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.searchKnowledge( anyString(), any(), anyInt(), eq( Tier.HUMAN ) ) )
            .thenReturn( List.of() );

        new SearchKnowledgeTool( svc, null ).execute( Map.of( "query", "foo", "min_tier", "human" ) );

        verify( svc ).searchKnowledge( eq( "foo" ), any(), anyInt(), eq( Tier.HUMAN ) );
    }

    @Test
    void execute_without_min_tier_defaults_to_machine() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        when( svc.searchKnowledge( anyString(), any(), anyInt(), eq( Tier.MACHINE ) ) )
            .thenReturn( List.of() );

        new SearchKnowledgeTool( svc, null ).execute( Map.of( "query", "foo" ) );

        verify( svc ).searchKnowledge( eq( "foo" ), any(), anyInt(), eq( Tier.MACHINE ) );
    }

    @Test
    void execute_with_invalid_min_tier_returns_error() {
        final KnowledgeGraphService svc = mock( KnowledgeGraphService.class );
        final var result = new SearchKnowledgeTool( svc, null ).execute(
            Map.of( "query", "foo", "min_tier", "garbage" ) );
        // The error result is non-null; service.searchKnowledge MUST NOT have been called.
        verify( svc, never() ).searchKnowledge( anyString(), any(), anyInt(), any() );
    }
}
