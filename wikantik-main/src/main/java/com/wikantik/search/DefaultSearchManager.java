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
package com.wikantik.search;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.filters.BasePageFilter;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.pages.PageManager;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.TextUtil;

import java.io.IOException;
import java.util.Properties;


/**
 *  Manages searching the Wiki.
 *
 *  @since 2.2.21.
 */
public class DefaultSearchManager extends BasePageFilter implements SearchManager {

    private static final Logger LOG = LogManager.getLogger( DefaultSearchManager.class );

    private SearchProvider searchProvider;

    /**
     *  Creates a new SearchManager.
     *
     *  @param engine The Engine that owns this SearchManager.
     *  @param properties The list of Properties.
     *  @throws FilterException If it cannot be instantiated.
     */
    public DefaultSearchManager( final Engine engine, final Properties properties ) throws FilterException {
        initialize( engine, properties );
        WikiEventManager.addWikiEventListener( engine.getManager( PageManager.class ), this );
    }


    /** {@inheritDoc} */
    @Override
    public final void initialize( final Engine newEngine, final Properties properties ) throws FilterException {
        this.engine = newEngine;
        loadSearchProvider(properties);

        try {
            searchProvider.initialize( newEngine, properties );
        } catch( final NoRequiredPropertyException | IOException e ) {
            LOG.error( e.getMessage(), e );
        }
    }

    private void loadSearchProvider( final Properties properties ) {
        // See if we're using Lucene, and if so, ensure that its index directory is up-to-date.
        final String providerClassName = TextUtil.getStringProperty( properties, PROP_SEARCHPROVIDER, DEFAULT_SEARCHPROVIDER );

        try {
            searchProvider = ClassUtil.buildInstance( "com.wikantik.search", providerClassName );
        } catch( final ReflectiveOperationException e ) {
            LOG.error( "Failed loading SearchProvider: {}", providerClassName, e );
            throw new IllegalStateException( "Could not load SearchProvider: " + providerClassName, e );
        }
        LOG.debug( "Loaded search provider {}", searchProvider );
    }

    /** {@inheritDoc} */
    @Override
    public SearchProvider getSearchEngine()
    {
        return searchProvider;
    }

    /** {@inheritDoc} */
    @Override
    public void actionPerformed( final WikiEvent event ) {
        if( event instanceof WikiPageEvent pageEvent ) {
            final String pageName = pageEvent.getPageName();
            if( event.getType() == WikiPageEvent.PAGE_DELETE_REQUEST ) {
                final Page deletedPage = engine.getManager( PageManager.class ).getPage( pageName );
                if( deletedPage != null ) {
                    pageRemoved( deletedPage );
                }
            }
            if( event.getType() == WikiPageEvent.PAGE_REINDEX ) {
                final Page reindexedPage = engine.getManager( PageManager.class ).getPage( pageName );
                if( reindexedPage != null ) {
                    reindexPage( reindexedPage );
                }
            }
        }
    }

}
