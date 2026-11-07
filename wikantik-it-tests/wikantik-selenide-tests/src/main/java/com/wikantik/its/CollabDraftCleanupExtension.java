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

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * In collab mode, purges every collaborative draft after each test, so a cancelled edit's draft never leaks into
 * the next test that opens the same page. A failed purge fails the test.
 */
public class CollabDraftCleanupExtension implements AfterEachCallback {

    @Override
    public void afterEach( final ExtensionContext context ) throws Exception {
        if ( CollabMode.enabled() ) {
            RestSeedHelper.purgeAllDrafts();
        }
    }
}
