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

package com.wikantik.diff;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.util.ClassUtil;

import java.io.IOException;
import java.util.Properties;


/**
 * Load, initialize and delegate to the DiffProvider that will actually do the work.
 */
public class DefaultDifferenceManager implements DifferenceManager {

    private static final Logger LOG = LogManager.getLogger( DefaultDifferenceManager.class );

    private DiffProvider provider;
    private final PageManager pageManager;

    /**
     * Creates a new DifferenceManager for the given engine.
     *
     * @param engine The Engine.
     * @param props  A set of properties.
     */
    public DefaultDifferenceManager( final Engine engine, final Properties props ) {
        this( engine.getManager( PageManager.class ), props, engine );
    }

    /**
     * Package-private constructor for testing — accepts collaborators directly.
     *
     * @param pageManager The PageManager to use for retrieving page text.
     * @param props       A set of properties.
     * @param engine      The Engine (used only to initialize the DiffProvider).
     */
    DefaultDifferenceManager( final PageManager pageManager, final Properties props, final Engine engine ) {
        this.pageManager = pageManager;
        loadProvider( props );
        initializeProvider( engine, props );

        LOG.info( "Using difference provider: {}", provider.getProviderInfo() );
    }

    /**
     * Package-private constructor for testing — accepts a pre-built DiffProvider directly,
     * bypassing class loading and initialization.
     *
     * @param pageManager  The PageManager to use for retrieving page text.
     * @param diffProvider The DiffProvider to use for generating diffs.
     */
    DefaultDifferenceManager( final PageManager pageManager, final DiffProvider diffProvider ) {
        this.pageManager = pageManager;
        this.provider = diffProvider;
    }

    private void loadProvider( final Properties props ) {
        final String providerClassName = props.getProperty( PROP_DIFF_PROVIDER, TraditionalDiffProvider.class.getName() );
        try {
            provider = ClassUtil.buildInstance( "com.wikantik.diff", providerClassName );
        } catch( final ReflectiveOperationException e ) {
            LOG.warn( "Failed loading DiffProvider, will use NullDiffProvider.", e );
        }

        if( provider == null ) {
            provider = new DiffProvider.NullDiffProvider();
        }
    }


    private void initializeProvider( final Engine engine, final Properties props ) {
        try {
            provider.initialize( engine, props );
        } catch( final NoRequiredPropertyException | IOException e ) {
            LOG.warn( "Failed initializing DiffProvider, will use NullDiffProvider.", e );
            provider = new DiffProvider.NullDiffProvider(); //doesn't need init'd
        }
    }

    /**
     * Returns valid XHTML string to be used in any way you please.
     *
     * @param context        The Wiki Context
     * @param firstWikiText  The old text
     * @param secondWikiText the new text
     * @return XHTML, or empty string, if no difference detected.
     */
    @Override
    public String makeDiff( final Context context, final String firstWikiText, final String secondWikiText ) {
        String diff;
        try {
            diff = provider.makeDiffHtml( context, firstWikiText, secondWikiText );

            if( diff == null ) {
                diff = "";
            }
        } catch( final Exception e ) {
            diff = "Failed to create a diff, check the logs.";
            LOG.warn( diff, e );
        }
        return diff;
    }

    /**
     *  Returns a diff of two versions of a page.
     *  <p>
     *  Note that the API was changed in 2.6 to provide a WikiContext object!
     *
     *  @param context The WikiContext of the page you wish to get a diff from
     *  @param version1 Version number of the old page.  If WikiPageProvider.LATEST_VERSION (-1), then uses current page.
     *  @param version2 Version number of the new page.  If WikiPageProvider.LATEST_VERSION (-1), then uses current page.
     *
     *  @return A HTML-ized difference between two pages.  If there is no difference, returns an empty string.
     */
    @Override
    public String getDiff( final Context context, final int version1, final int version2 ) {
        final String page = context.getPage().getName();
        String page1 = pageManager.getPureText( page, version1 );
        final String page2 = pageManager.getPureText( page, version2 );

        // Kludge to make diffs for new pages to work this way.
        if( version1 == PageProvider.LATEST_VERSION ) {
            page1 = "";
        }

        return makeDiff( context, page1, page2 );
    }

}

