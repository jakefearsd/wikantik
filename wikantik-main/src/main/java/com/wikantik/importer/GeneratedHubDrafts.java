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
package com.wikantik.importer;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wikantik.importer.VaultClusterPlanner.GeneratedHub;

/** Builds the page drafts for hubs the import generates for folders that have no folder note. */
final class GeneratedHubDrafts {

    private static final String PREFIX = "Notes imported from the Obsidian folder ";
    private static final String PAD = " Each note became a page in this cluster.";
    private static final String ELLIPSIS = "...";
    /** The schema's summary bounds (FrontmatterSchema): shorter or longer draws a warning. */
    private static final int MIN = 50;
    private static final int MAX = 160;

    private GeneratedHubDrafts() {
    }

    static PageDraft draft( final GeneratedHub hub ) {
        final Map< String, Object > meta = new LinkedHashMap<>();
        meta.put( "type", "hub" );
        meta.put( "cluster", hub.cluster() );
        meta.put( "title", hub.folderName() );
        meta.put( "summary", summary( hub.folderName() ) );
        return new PageDraft( hub.name(), null, meta, "# " + hub.folderName() + "\n", true );
    }

    /** The spec text, padded when it is shorter than 50 characters and clamped when longer than 160. */
    static String summary( final String folderName ) {
        final String text = PREFIX + folderName + ".";
        if ( text.length() > MAX ) {
            final int keep = MAX - PREFIX.length() - ELLIPSIS.length();
            return PREFIX + folderName.substring( 0, keep ).stripTrailing() + ELLIPSIS;
        }
        return text.length() < MIN ? text + PAD : text;
    }
}
