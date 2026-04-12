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

import com.wikantik.pages.Page;
import com.wikantik.pages.haddock.ViewWikiPage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;

import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;


/**
 * Anonymous view related tests for Apache JSPWiki.
 *
 * <p>The legacy {@code anonymousReaderView} test was removed when the haddock
 * "Show Reader View" feature was dropped during the React SPA migration — the
 * SPA has no equivalent toggle, and the sidebar is controlled by the user's
 * collapse/expand preference instead.
 */
public class AnonymousViewIT extends WithIntegrationTestSetup {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anonymousView() {
        final ViewWikiPage main = ViewWikiPage.open( "Main" );
        Assertions.assertEquals( "Wikantik: Main", main.title() );
        Assertions.assertEquals( "Main", main.wikiTitle() );

        Assertions.assertTrue( main.wikiPageContent().contains( "You have successfully installed" ) );
        final ViewWikiPage about = main.navigateTo( "Wikantik" );
        Assertions.assertTrue( about.wikiPageContent().contains( "This Wiki is done using" ) );
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anonymousViewImage() throws Exception {
        final File file = Page.download( Page.baseUrl() + "/images/wikantik_logo_s.png" );
        Assertions.assertTrue( file.exists() );
    }

}
