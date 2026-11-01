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

import com.wikantik.pages.spa.EditWikiPage;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Leaves the IT user's server editor preference cleared after any test that pinned an editor mode.
 *
 * <p>{@link EditWikiPage#pinMode} PUTs the mode to the signed-in user's profile (and, signed out, leaves a local
 * choice that the app migrates into the profile at the next sign-in), so a test that ran in {@link EditorMode#LIVE}
 * would otherwise hand that preference to every later test. The clear goes out of the browser with Janne's basic-auth
 * credentials — the only account ITs sign the browser into — so it works whatever the test left the browser in
 * (signed out, driver closed). A failed clear fails the test.</p>
 */
public class EditorModeCleanupExtension implements AfterEachCallback {

    @Override
    public void afterEach( final ExtensionContext context ) throws Exception {
        if ( EditWikiPage.takeModePinned() ) {
            RestSeedHelper.clearEditorModePreference();
        }
    }
}
