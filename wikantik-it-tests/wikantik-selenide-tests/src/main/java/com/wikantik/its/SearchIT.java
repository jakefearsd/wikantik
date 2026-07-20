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
package com.wikantik.its;

import com.wikantik.its.environment.Env;
import com.wikantik.pages.spa.ViewWikiPage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Search-related tests for Apache JSPWiki
 */
public class SearchIT extends WithIntegrationTestSetup {

    @Test
    void performSearches() throws Exception {
        final String text = "Congratulations - with cuchuflus! :: " + System.currentTimeMillis();

        // first, search for a term indexed on startup — retry until the initial
        // search indexing has caught up (condition-based, no fixed sleep).
        final ViewWikiPage main = ViewWikiPage.open( "Main" )
                                              .searchForUntilFound( "Congratulations", "Main" )
                                              .navigateTo( "Main" );

        // second, edit a page and search for that newly indexed page — retry until
        // the per-change indexing (indexdelay=1s) has caught up.
        main.editPage().saveText( text );
        main.searchForUntilFound( "cuchuflus", "Main" )
            .navigateTo( "Main" );

        Assertions.assertEquals( text, main.wikiPageContent() );
    }

}
