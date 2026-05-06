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
package com.wikantik.page.subsystem.lifecycle;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.WikiException;

/**
 * Orchestrates the page-save lifecycle: pre-save filtering, persistence,
 * post-save filtering, and search re-indexing.
 *
 * <p>Extracted from {@code DefaultPageManager} in Phase 5 Checkpoint 3 of the
 * wikantik-main subsystem decomposition.</p>
 */
public interface PageLifecycle {

    /** @see com.wikantik.api.managers.PageManager#saveText(Context, String) */
    void saveText( Context context, String text ) throws WikiException;
}
