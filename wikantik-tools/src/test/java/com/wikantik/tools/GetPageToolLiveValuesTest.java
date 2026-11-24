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
package com.wikantik.tools;

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.managers.PageManager;
import com.wikantik.core.registry.EngineServiceRegistry;
import com.wikantik.livevalues.StubLiveValues;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static com.wikantik.livevalues.StubLiveValues.TOKEN;
import static com.wikantik.livevalues.StubLiveValues.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code get_page} shows live values, not tokens, in the summary and the text. */
class GetPageToolLiveValuesTest {

    private static Map< String, Object > run( final LiveValues lv ) {
        final WikiEngine engine = mock( WikiEngine.class );
        final PageManager pm = mock( PageManager.class );
        final Page page = mock( Page.class );
        final EngineServiceRegistry registry = new EngineServiceRegistry();
        if ( lv != null ) {
            registry.put( LiveValues.class, lv );
        }
        when( engine.serviceRegistry() ).thenReturn( registry );
        when( engine.getManager( PageManager.class ) ).thenReturn( pm );
        when( pm.getPage( "Main" ) ).thenReturn( page );
        when( page.getName() ).thenReturn( "Main" );
        when( pm.getPureText( "Main", -1 ) ).thenReturn(
            "---\nsummary: Holds " + TOKEN + " articles\n---\nBody " + TOKEN + " pages" );
        final GetPageTool tool = new GetPageTool( engine, new ToolsConfig( new Properties() ) ) {
            @Override boolean canView( final HttpServletRequest r, final String n ) { return true; }
            @Override boolean canEdit( final HttpServletRequest r, final String n ) { return false; }
            @Override PublicIdentity publicIdentity() { return mock( PublicIdentity.class ); }
        };
        return tool.execute( "Main", 0, mock( HttpServletRequest.class ) );
    }

    @Test
    void summaryAndTextAreExpanded() {
        final Map< String, Object > out = run( new StubLiveValues() );
        assertEquals( "Holds " + VALUE + " articles", out.get( "summary" ) );
        assertEquals( "Body " + VALUE + " pages", out.get( "text" ) );
    }

    @Test
    void withoutTheServiceTokensStayVerbatim() {
        final Map< String, Object > out = run( null );
        assertTrue( out.get( "text" ).toString().contains( TOKEN ) );
        assertTrue( out.get( "summary" ).toString().contains( TOKEN ) );
    }
}
