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

import java.util.Optional;

/**
 * What {@link ObsidianPageConverter} needs to know about link targets while rewriting a page's
 * markdown into Obsidian form. Implemented by {@code ExportService} against the live wiki; faked
 * in tests.
 */
public interface ExportLinkContext {

    boolean inExport( String pageName );

    /** Heading text on {@code pageName} whose slug is {@code slug}, if any. */
    Optional< String > headingText( String pageName, String slug );

    /** Obsidian link target for an attachment ("file.png" or "_attachments/Page/file.png"); empty if not an attachment. */
    Optional< String > attachmentTarget( String pageName, String fileName );

    Optional< String > slugForCanonicalId( String canonicalId );

    /**
     * The vault file basename (without {@code .md}) the layout chose for an in-export page —
     * differs from the page name after sanitising or a case-collision {@code ~N} suffix. Empty
     * when {@code pageName} is not in the export. Links to in-export pages must target this.
     */
    Optional< String > vaultBasename( String pageName );

    String liveUrl( String pageName );

    UnresolvedLinkMode unresolvedMode();
}
