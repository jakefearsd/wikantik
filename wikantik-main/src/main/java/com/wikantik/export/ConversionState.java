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
package com.wikantik.export;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Mutable state threaded through {@link ObsidianPageConverter} and {@link ObsidianLinkRenderer}
 * for a single page conversion, so those helpers don't each need half a dozen parameters.
 * Package-private: an implementation detail of the Obsidian export converter, not part of its
 * public surface.
 */
final class ConversionState {
    final String currentPage;
    final ExportLinkContext ctx;
    final LinkedHashSet< AttachmentRef > attachments = new LinkedHashSet<>();
    final List< String > warnings = new ArrayList<>();
    final List< String > footnoteLines = new ArrayList<>();
    int citeCounter;

    ConversionState( final String currentPage, final ExportLinkContext ctx ) {
        this.currentPage = currentPage;
        this.ctx = ctx;
    }
}
