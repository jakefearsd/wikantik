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
import com.codeborne.selenide.junit5.ScreenShooterExtension;
import com.wikantik.its.environment.Env;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith( ScreenShooterExtension.class )
public class WithIntegrationTestSetup {

    /**
     * Closes any WebDriver the previous test class left open, so each class
     * starts with a clean browser session (no leaked cookies, localStorage, or
     * React auth state). Selenide will lazily spin up a fresh driver on the
     * next {@code Selenide.open(...)} call.
     *
     * <p>The React SPA caches authentication state in cookies and memory.
     * Without this reset, a test class that authenticates (e.g. {@code EditIT}
     * logging in as Janne) would leave later classes (e.g. {@code LoginIT})
     * believing they are already logged in when they expect an anonymous
     * starting state.
     */
    @BeforeAll
    @DisabledOnOs(OS.WINDOWS)
    public static void setUp() {
        Env.setUp();
        Selenide.closeWebDriver();
    }

}
