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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.bundle.ContextBundle;
import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.knowledge.briefing.BriefingConfig.BriefingLimits;
import com.wikantik.livevalues.StubLiveValues;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.wikantik.livevalues.StubLiveValues.TOKEN;
import static com.wikantik.livevalues.StubLiveValues.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A pinned page's briefing item shows live values, not the token, in its summary and body. */
class BriefingLiveValuesTest {

    private static ContextBriefing briefing( final StubLiveValues lv ) {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPage( "Billing" ) ).thenReturn( mock( Page.class ) );
        when( pm.getPureText( "Billing", PageProvider.LATEST_VERSION ) ).thenReturn(
            "---\ntitle: Billing\nsummary: We have " + TOKEN + " articles\n---\nBody " + TOKEN + " pages.\n" );
        final DefaultBriefingAssemblyService svc = new DefaultBriefingAssemblyService(
            q -> new ContextBundle( q, List.of() ), null, null, pm, BriefingLimits.defaults(), () -> lv );
        return svc.assemble( new BriefingRequest( List.of( "Billing" ), null, null, null, null ) );
    }

    @Test
    void pinnedItemBodyAndSummaryAreExpanded() {
        final BriefingItem item = briefing( new StubLiveValues() ).items().get( 0 );
        assertEquals( "We have " + VALUE + " articles", item.summary() );
        assertTrue( item.content().contains( "Body " + VALUE + " pages." ), item.content() );
        assertFalse( item.content().contains( "{{wiki:" ), item.content() );
    }

    @Test
    void withoutLiveValuesTheTokenIsVerbatim() {
        assertTrue( briefing( null ).items().get( 0 ).content().contains( TOKEN ) );
    }
}
