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
package com.wikantik.test;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.frontmatter.FrontmatterWriter;
import com.wikantik.pages.PageSaveHelper;
import com.wikantik.pages.SaveOptions;
import com.wikantik.pages.VersionConflictException;

import java.util.Map;

/**
 * Stub implementation of {@link PageSaveHelper} for unit testing.
 *
 * <p>Overrides {@link #saveText(String, String, SaveOptions)} to work with
 * {@link StubPageManager} directly, avoiding any need for a running WikiEngine,
 * WikiContext, or {@code Wiki.contents().page()} SPI call.
 *
 * @since 3.0.7
 */
public class StubPageSaveHelper extends PageSaveHelper {

    private final StubPageManager pm;

    public StubPageSaveHelper( final StubPageManager pm ) {
        super( null );   // engine won't be used — saveText is fully overridden
        this.pm = pm;
    }

    @Override
    public Page saveText( final String pageName, final String text, final SaveOptions options ) throws WikiException {
        // Optimistic locking: version check
        if( options.expectedVersion() > 0 ) {
            final Page current = pm.getPage( pageName );
            if( current != null && Math.max( current.getVersion(), 1 ) != options.expectedVersion() ) {
                throw new VersionConflictException( pageName, Math.max( current.getVersion(), 1 ),
                        options.expectedVersion(), false );
            }
        }

        // Optimistic locking: content hash check
        if( options.expectedContentHash() != null ) {
            final String currentText = pm.getPureText( pageName, -1 );
            final String currentHash = computeContentHash( currentText != null ? currentText : "" );
            if( !currentHash.equals( options.expectedContentHash() ) ) {
                throw new VersionConflictException( pageName, -1, -1, true );
            }
        }

        // Frontmatter handling
        String effectiveText = text;
        if( options.metadata() != null ) {
            final Map< String, Object > effectiveMetadata = options.replaceMetadata()
                    ? options.metadata()
                    : mergeMetadata( pm, pageName, options.metadata() );
            effectiveText = FrontmatterWriter.write( effectiveMetadata, text );
        }

        // Save to stub
        pm.savePage( pageName, effectiveText );
        final Page page = pm.getPage( pageName );
        if( options.author() != null ) {
            page.setAuthor( options.author() );
        }
        if( options.markupSyntax() != null ) {
            page.setAttribute( Page.MARKUP_SYNTAX, options.markupSyntax() );
        }
        if( options.changeNote() != null ) {
            page.setAttribute( Page.CHANGENOTE, options.changeNote() );
        }
        return page;
    }
}
