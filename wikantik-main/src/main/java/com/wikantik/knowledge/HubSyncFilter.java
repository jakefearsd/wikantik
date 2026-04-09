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
package com.wikantik.knowledge;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.filters.PageFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A postSave {@link PageFilter} that keeps hub↔member relationships in sync.
 * When a page's {@code hubs} or {@code related} frontmatter field changes,
 * this filter updates the corresponding pages to maintain bidirectional links.
 *
 * <p>This class is a stub — full implementation is pending.</p>
 */
public class HubSyncFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( HubSyncFilter.class );

    private final Function< String, String > pageReader;
    private final BiConsumer< String, String > pageWriter;

    /**
     * Constructs a new HubSyncFilter.
     *
     * @param pageReader function that returns the raw content of a page by name, or {@code null} if absent
     * @param pageWriter consumer that saves raw content for a page by name
     */
    public HubSyncFilter( final Function< String, String > pageReader,
                          final BiConsumer< String, String > pageWriter ) {
        this.pageReader = pageReader;
        this.pageWriter = pageWriter;
    }

    /**
     * Synchronises hub↔member relationships after a page save.
     *
     * @param pageName   the name of the page that was just saved
     * @param newContent the new raw page content
     * @param oldContent the previous raw page content (before the save)
     */
    public void syncAfterSave( final String pageName, final String newContent, final String oldContent ) {
        // TODO: implement bidirectional hub/member sync
        LOG.warn( "HubSyncFilter.syncAfterSave called but not yet implemented for page '{}'", pageName );
    }

    @Override
    public void postSave( final Context context, final String content ) throws FilterException {
        // TODO: wire syncAfterSave from postSave using old content from context
    }
}
