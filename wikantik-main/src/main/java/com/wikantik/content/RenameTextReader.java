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
package com.wikantik.content;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Reads page text for a rename through the API that reports a provider failure, so a failed read aborts instead of writing "". */
final class RenameTextReader {

    private static final Logger LOG = LogManager.getLogger( RenameTextReader.class );

    private RenameTextReader() {
    }

    /**
     * The latest text of {@code pageName}.
     *
     * @throws WikiException when the text cannot be read (the rename, or this referrer's update, is abandoned)
     */
    static String textOf( final Engine engine, final String pageName ) throws WikiException {
        try {
            final String text = PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPageText( pageName, PageProvider.LATEST_VERSION );
            return text == null ? "" : text;
        } catch( final ProviderException e ) {
            LOG.warn( "The text of page {} could not be read; nothing is written: {}", pageName, e.getMessage() );
            throw new WikiException( "The text of page " + pageName + " could not be read, so it was not renamed: " + e.getMessage(), e );
        }
    }

    /** The latest text of a referrer, or "" (logged) when it cannot be read: nothing in "" is rewritten, so that referrer is left as it is. */
    static String referrerText( final Engine engine, final String pageName ) {
        try {
            return textOf( engine, pageName );
        } catch( final WikiException e ) {
            LOG.warn( "A referrer was not updated because its text could not be read: {}", e.getMessage() );
            return "";
        }
    }
}
