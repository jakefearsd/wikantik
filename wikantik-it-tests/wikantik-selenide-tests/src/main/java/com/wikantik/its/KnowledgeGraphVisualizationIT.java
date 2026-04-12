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

import com.codeborne.selenide.Selenide;
import com.wikantik.its.environment.Env;
import com.wikantik.pages.haddock.ViewWikiPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;

import static com.codeborne.selenide.Condition.*;
import static com.codeborne.selenide.Selenide.*;

class KnowledgeGraphVisualizationIT extends WithIntegrationTestSetup {

    @BeforeEach
    void login() {
        Selenide.closeWebDriver();
        ViewWikiPage.open( "Main" )
            .clickOnLogin()
            .performLogin( Env.LOGIN_JANNE_USERNAME, Env.LOGIN_JANNE_PASSWORD );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void graphView_loadsFullSnapshot() {
        open( Env.TESTS_BASE_URL + "/graph" );
        $( ".graph-canvas-container" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void graphView_focusParamSelectsNode() {
        open( Env.TESTS_BASE_URL + "/graph?focus=Main" );
        $( ".graph-details-drawer" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
        $( ".graph-details-drawer" ).shouldHave( text( "Main" ) );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void graphView_clickNodeOpensDrawer() {
        open( Env.TESTS_BASE_URL + "/graph" );
        $( ".graph-canvas-container" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
        executeJavaScript( "if (window.cy) { window.cy.nodes()[0].emit('tap'); }" );
        $( ".graph-details-drawer" )
                .shouldBe( visible, Duration.ofSeconds( 5 ) );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void graphView_noErrorStateWhenAuthenticated() {
        open( Env.TESTS_BASE_URL + "/graph" );
        $( ".graph-canvas-container" )
                .shouldBe( visible, Duration.ofSeconds( 15 ) );
        $( ".graph-error-state" ).shouldNotBe( visible );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void graphView_sidebarLinkIncludesFocus() {
        ViewWikiPage.open( "Main" );
        $$( "a" ).findBy( text( "Knowledge Graph" ) )
                .shouldBe( visible )
                .shouldHave( attribute( "href", "/graph?focus=Main" ) );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void graphView_anonymousShowsSignInPrompt() {
        Selenide.closeWebDriver();
        open( Env.TESTS_BASE_URL + "/graph" );
        $( ".graph-error-state" )
                .shouldBe( visible, Duration.ofSeconds( 10 ) );
        $( ".graph-error-state" ).shouldHave( text( "Sign in" ) );
    }
}
