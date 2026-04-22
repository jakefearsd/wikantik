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
package com.wikantik.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.util.TextUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


/**
 *  Displays the pages referring to the current page.
 *
 *  <p>Parameters</p>
 *  <ul>
 *    <li><b>name</b> - Name of the root page. Default name of calling page
 *    <li><b>type</b> - local|externalattachment
 *    <li><b>depth</b> - How many levels of pages to be parsed.
 *    <li><b>include</b> - Include only these pages. (eg. include='UC.*|BP.*' )
 *    <li><b>exclude</b> - Exclude with this pattern. (eg. exclude='LeftMenu' )
 *    <li><b>format</b> -  full|compact, FULL now expands all levels correctly
 *  </ul>
 *
 */
public class ReferredPagesPlugin implements Plugin {

    private static final Logger LOG = LogManager.getLogger( ReferredPagesPlugin.class );
    private Engine engine;
    private int depth;
    private final HashSet< String > exists  = new HashSet<>();
    private Pattern includePattern;
    private Pattern excludePattern;
    private boolean formatCompact = true;
    private boolean formatSort;

    /** The parameter name for the root page to start from.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_ROOT = "page";

    /** The parameter name for the depth.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_DEPTH = "depth";

    /** The parameter name for the type of the references.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_TYPE = "type";

    /** The parameter name for the included pages.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_INCLUDE = "include";

    /** The parameter name for the excluded pages.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_EXCLUDE = "exclude";

    /** The parameter name for the format.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_FORMAT = "format";

    /** Parameter name for setting the number of columns that will be displayed by the plugin.  Value is <tt>{@value}</tt>. Available since 2.11.0. */
    public static final String PARAM_COLUMNS = "columns";

    /** The minimum depth. Value is <tt>{@value}</tt>. */
    public static final int MIN_DEPTH = 1;

    /** The maximum depth. Value is <tt>{@value}</tt>. */
    public static final int MAX_DEPTH = 8;

    /**
     *  {@inheritDoc}
     */
    @Override
    public String execute( final Context context, final Map< String, String > params ) throws PluginException {
        engine = context.getEngine();
        final Page page = context.getPage();
        if( page == null ) {
            return "";
        }

        final PluginParams p = PluginParams.parse( params, page.getName() );
        this.depth         = p.depth;
        this.formatCompact = p.formatCompact;
        this.formatSort    = p.formatSort;
        this.includePattern = compilePattern( p.includePattern, "include" );
        this.excludePattern = compilePattern( p.excludePattern, "exclude" );

        LOG.debug( "Fetching referred pages for {} with a depth of {} with include pattern of {} with exclude pattern of {} with {} columns",
                   p.rootname, p.depth, p.includePattern, p.excludePattern, p.columns );

        final StringBuilder result = new StringBuilder( 1024 );
        result.append( renderHeader( context, p ) );
        exists.add( p.rootname );
        getReferredPages( context, p.rootname, 0, result );
        result.append( "</div>\n" );
        return result.toString();
    }

    /** Renders the wrapping div and the root page link. */
    private String renderHeader( final Context context, final PluginParams p ) {
        final String href = context.getViewURL( p.rootname );
        final String title = "ReferredPagesPlugin: depth[" + p.depth
                + "] include[" + p.includePattern + "] exclude[" + p.excludePattern
                + "] format[" + ( p.formatCompact ? "compact" : "full" )
                + ( p.formatSort ? " sort" : "" ) + "]";

        final StringBuilder sb = new StringBuilder( 256 );
        if ( p.columns > 1 ) {
            sb.append( "<div class=\"ReferredPagesPlugin\" style=\"" )
              .append( "columns:" ).append( p.columns ).append( ';' )
              .append( "moz-columns:" ).append( p.columns ).append( ';' )
              .append( "webkit-columns:" ).append( p.columns ).append( ';' )
              .append( "\">\n" );
        } else {
            sb.append( "<div class=\"ReferredPagesPlugin\">\n" );
        }
        sb.append( "<a class=\"wikipage\" href=\"" ).append( href )
          .append( "\" title=\"" ).append( TextUtil.replaceEntities( title ) ).append( "\">" )
          .append( TextUtil.replaceEntities( p.rootname ) ).append( "</a>\n" );
        return sb.toString();
    }

    private static Pattern compilePattern( final String regex, final String which ) throws PluginException {
        try {
            return Pattern.compile( regex );
        } catch ( final PatternSyntaxException e ) {
            throw new PluginException( "Illegal " + which + " pattern detected.", e );
        }
    }

    /**
     * Retrieves a list of all referred pages. Is called recursively depending on the depth parameter.
     */
    private void getReferredPages( final Context context, final String pagename, final int currentDepth, final StringBuilder result ) {
        if ( currentDepth >= this.depth || pagename == null
                || !engine.getManager( PageManager.class ).wikiPageExists( pagename ) ) {
            return;
        }
        final ReferenceManager mgr = engine.getManager( ReferenceManager.class );
        handleLinks( context, mgr.findRefersTo( pagename ), currentDepth + 1, pagename, result );
    }

    private void handleLinks( final Context context, final Collection< String > links, final int currentDepth,
                              final String pagename, final StringBuilder result ) {
        // Track links already emitted within this invocation so the same page isn't listed twice at this level.
        final HashSet< String > localLinkSet = new HashSet<>();
        localLinkSet.add( pagename );

        final ArrayList< String > allLinks = new ArrayList<>( links );
        if ( formatSort ) {
            context.getEngine().getManager( PageManager.class ).getPageSorter().sort( allLinks );
        }

        boolean isUL = false;
        for ( final String link : allLinks ) {
            if ( !localLinkSet.add( link ) || !isRenderable( link ) ) {
                continue;
            }
            final boolean alreadySeen = exists.contains( link );
            // Compact mode: each page appears once in the whole tree; skip repeats entirely.
            if ( alreadySeen && formatCompact ) {
                continue;
            }
            if ( !isUL ) {
                isUL = true;
                result.append( "<ul>\n" );
            }
            appendLinkItem( context, link, alreadySeen, result );
            getReferredPages( context, link, currentDepth, result );
            result.append( "\n</li>\n" );
        }

        if ( isUL ) {
            result.append( "</ul>\n" );
        }
    }

    /** Returns whether the link points at a page that exists and survives the include/exclude filters. */
    private boolean isRenderable( final String link ) {
        return engine.getManager( PageManager.class ).wikiPageExists( link )
                && !excludePattern.matcher( link ).matches()
                && includePattern.matcher( link ).matches();
    }

    /** Writes the opening <li> for a link; for the first sighting, emits the anchor and records the page in {@link #exists}. */
    private void appendLinkItem( final Context context, final String link, final boolean alreadySeen,
                                 final StringBuilder result ) {
        if ( alreadySeen ) {
            // See https://www.w3.org/wiki/HTML_lists for proper nesting of UL and LI.
            result.append( "<li> " ).append( TextUtil.replaceEntities( link ) ).append( '\n' );
            return;
        }
        final String href = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), link );
        result.append( "<li><a class=\"wikipage\" href=\"" ).append( href ).append( "\">" )
              .append( TextUtil.replaceEntities( link ) ).append( "</a>\n" );
        exists.add( link );
    }

    /** Parsed, validated invocation parameters. */
    private record PluginParams( String rootname, int depth, int columns,
                                 String includePattern, String excludePattern,
                                 boolean formatCompact, boolean formatSort ) {

        static PluginParams parse( final Map< String, String > params, final String defaultRoot ) {
            final String rootname = params.getOrDefault( PARAM_ROOT, defaultRoot );

            final String format = params.getOrDefault( PARAM_FORMAT, "" );
            final boolean compact = !format.contains( "full" );
            final boolean sort    = format.contains( "sort" );

            int depth = TextUtil.parseIntParameter( params.get( PARAM_DEPTH ), MIN_DEPTH );
            if ( depth > MAX_DEPTH ) {
                depth = MAX_DEPTH;
            }

            final String columnsStr = params.get( PARAM_COLUMNS );
            final int columns = columnsStr != null ? TextUtil.parseIntParameter( columnsStr, 0 ) : 0;

            final String include = params.getOrDefault( PARAM_INCLUDE, ".*" );
            final String exclude = params.getOrDefault( PARAM_EXCLUDE, "^$" );

            return new PluginParams( rootname, depth, columns, include, exclude, compact, sort );
        }
    }

}
