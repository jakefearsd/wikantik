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
package com.wikantik.api.pages;

import com.wikantik.api.core.Page;

/**
 * The result of a save: the version this call wrote (or, when {@code unchanged}, the existing latest version).
 *
 * <p>{@code version} is the provider's version number as reported: an unversioned provider can report {@code -1}
 * (no version history), so callers that need a version number clamp it (e.g. {@code Math.max(version, 1)}).</p>
 *
 * @param page      the page at {@code version}; may be {@code null} only when nothing was written and nothing exists
 * @param version   the version number written, 0 when nothing was written and nothing exists, possibly {@code -1} on
 *                  an unversioned provider
 * @param unchanged true when the call wrote nothing (identical text, or an empty page that is not created)
 */
public record SaveOutcome( Page page, int version, boolean unchanged ) { }
