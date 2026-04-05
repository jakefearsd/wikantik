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
package com.wikantik.url;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.InternalWikiException;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.util.TextUtil;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Properties;
import java.util.Set;


/**
 *  Provides a way to do short URLs of the form /wiki/PageName.
 *
 *  @since 2.2
 */
public class ShortURLConstructor extends DefaultURLConstructor {
    
    private static final String DEFAULT_PREFIX = "wiki/";
    private static final Logger LOG = LogManager.getLogger( ShortURLConstructor.class );
    
    /** Contains the path part after the Wikantik base URL */
    protected String urlPrefix = "";

    /** Contexts where a null name returns the base URL instead of throwing. */
    private static final Set< String > NULL_NAME_CONTEXTS = Set.of(
            ContextEnum.PAGE_VIEW.getRequestContext(),
            ContextEnum.PAGE_PREVIEW.getRequestContext()
    );

    /** Context → URL pattern, built during {@link #initialize}. */
    private Map< String, String > urlPatterns;

    /**
     *  This corresponds to your WikiServlet path.  By default, it is assumed to be "wiki/", but you can set it to whatever you
     *  like - including an empty name.
     */
    public static final String PROP_PREFIX = "wikantik.shortURLConstructor.prefix";

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine engine, final Properties properties ) {
        super.initialize( engine, properties );

        urlPrefix = TextUtil.getStringProperty( properties, PROP_PREFIX, null );

        if( urlPrefix == null ) {
            urlPrefix = DEFAULT_PREFIX;
        }

        LOG.info("Short URL prefix path={} (You can use {} to override this)", urlPrefix, PROP_PREFIX);

        final String viewurl = "%p" + urlPrefix + "%n";
        urlPatterns = Map.ofEntries(
                Map.entry( ContextEnum.PAGE_VIEW.getRequestContext(),         viewurl ),
                Map.entry( ContextEnum.PAGE_PREVIEW.getRequestContext(),      viewurl + "?do=Preview" ),
                Map.entry( ContextEnum.PAGE_EDIT.getRequestContext(),         "%pedit/%n" ),
                Map.entry( ContextEnum.PAGE_ATTACH.getRequestContext(),       "%uattach/%n" ),
                Map.entry( ContextEnum.PAGE_INFO.getRequestContext(),         viewurl + "?do=PageInfo" ),
                Map.entry( ContextEnum.PAGE_DIFF.getRequestContext(),         "%pdiff/%n" ),
                Map.entry( ContextEnum.PAGE_NONE.getRequestContext(),         "%u%n" ),
                Map.entry( ContextEnum.PAGE_UPLOAD.getRequestContext(),       viewurl + "?do=Upload" ),
                Map.entry( ContextEnum.PAGE_COMMENT.getRequestContext(),      viewurl + "?do=Comment" ),
                Map.entry( ContextEnum.WIKI_LOGIN.getRequestContext(),        "%plogin?redirect=%n" ),
                Map.entry( ContextEnum.PAGE_DELETE.getRequestContext(),       viewurl + "?do=Delete" ),
                Map.entry( ContextEnum.PAGE_CONFLICT.getRequestContext(),     viewurl + "?do=PageModified" ),
                Map.entry( ContextEnum.WIKI_PREFS.getRequestContext(),        "%ppreferences" ),
                Map.entry( ContextEnum.WIKI_FIND.getRequestContext(),         "%psearch" ),
                Map.entry( ContextEnum.WIKI_ERROR.getRequestContext(),        "%uerror" ),
                Map.entry( ContextEnum.WIKI_CREATE_GROUP.getRequestContext(), viewurl + "?do=NewGroup" ),
                Map.entry( ContextEnum.GROUP_DELETE.getRequestContext(),      viewurl + "?do=DeleteGroup" ),
                Map.entry( ContextEnum.GROUP_EDIT.getRequestContext(),        viewurl + "?do=EditGroup" ),
                Map.entry( ContextEnum.GROUP_VIEW.getRequestContext(),        viewurl + "?do=Group&group=%n" )
        );
    }

    /**
     *  {@inheritDoc}
     *
     *  Provides short-URL routing: page views use {@code /wiki/PageName} style paths
     *  rather than the default {@code Wiki.jsp?page=PageName} pattern.
     */
    @Override
    protected String makeBaseURL( final String context, final String name ) {
        if( name == null && NULL_NAME_CONTEXTS.contains( context ) ) {
            return doReplacement( "%u", "" );
        }
        final String pattern = urlPatterns.get( context );
        if( pattern == null ) {
            throw new InternalWikiException( "Requested unsupported context " + context );
        }
        return doReplacement( pattern, name );
    }

    /**
     *  {@inheritDoc}
     *
     *  In the short-URL scheme, {@code PAGE_VIEW} URLs also use query-string style
     *  parameters (i.e. {@code ?key=value} rather than {@code &amp;key=value}).
     */
    @Override
    protected boolean usesQueryPrefix( final String context ) {
        return super.usesQueryPrefix( context ) || context.equals( ContextEnum.PAGE_VIEW.getRequestContext() );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String parsePage( final String context, final HttpServletRequest request, final Charset encoding ) {
        final String pagereq = request.getParameter( "page" );
        if( pagereq == null ) {
            return URLConstructor.parsePageFromURL( request, encoding );
        }

        return pagereq;
    }

}
