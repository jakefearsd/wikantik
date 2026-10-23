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
package com.wikantik.citation;

import com.wikantik.WikiEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.event.WikiEventManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.pagegraph.spine.DefaultStructuralIndexService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CitationWiringHelperTest {

    private CitationWiringHelper.Wired wire( final PageManager pm, final FilterManager fm,
                                             final StructuralIndexService idx ) {
        return CitationWiringHelper.wireCitations( mock( WikiEngine.class ), new Properties(),
                mock( DataSource.class ), pm, fm, idx, null );
    }

    @Test
    void deletesReachCitationsThroughTheStructuralIndexRetirementHook() {
        final PageManager pm = mock( PageManager.class );
        final FilterManager fm = mock( FilterManager.class );
        final DefaultStructuralIndexService spine = mock( DefaultStructuralIndexService.class );

        final CitationWiringHelper.Wired wired = wire( pm, fm, spine );
        try {
            assertNotNull( wired );
            verify( spine ).addPageRetirementListener( any() );
        } finally {
            WikiEventManager.removeWikiEventListener( pm, wired.listener() );
            WikiEventManager.removeWikiEventListener( fm, wired.listener() );
        }
    }

    @Test
    void aNonDefaultStructuralIndexStillWiresTheRestOfTheSubsystem() {
        final PageManager pm = mock( PageManager.class );
        final FilterManager fm = mock( FilterManager.class );

        final CitationWiringHelper.Wired wired = wire( pm, fm, mock( StructuralIndexService.class ) );
        try {
            assertNotNull( wired );
        } finally {
            WikiEventManager.removeWikiEventListener( pm, wired.listener() );
            WikiEventManager.removeWikiEventListener( fm, wired.listener() );
        }
    }
}
