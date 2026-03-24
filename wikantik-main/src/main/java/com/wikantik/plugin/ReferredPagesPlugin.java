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
import com.wikantik.pages.PageManager;
import com.wikantik.references.ReferenceManager;
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
    private final StringBuilder result  = new StringBuilder( 1024 );
    private Pattern includePattern;
    private Pattern excludePattern;
    private int items;
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

        // parse parameters
        String rootname = params.get( PARAM_ROOT );
        if( rootname == null ) {
            rootname = page.getName() ;
        }

        String format = params.get( PARAM_FORMAT );
        if( format == null) {
            format = "";
        }
        if( format.contains( "full" ) ) {
            formatCompact = false ;
        }
        if( format.contains( "sort" ) ) {
            formatSort = true  ;
        }

        depth = TextUtil.parseIntParameter( params.get( PARAM_DEPTH ), MIN_DEPTH );
        if( depth > MAX_DEPTH ) {
            depth = MAX_DEPTH;
        }

        String includePatternStr = params.get(PARAM_INCLUDE);
        if( includePatternStr == null ) {
            includePatternStr = ".*";
        }

        String excludePatternStr = params.get(PARAM_EXCLUDE);
        if( excludePatternStr == null ) {
            excludePatternStr = "^$";
        }

        final String columns = params.get( PARAM_COLUMNS );
        if( columns != null ) {
            items = TextUtil.parseIntParameter( columns, 0 );
        }

        LOG.debug( "Fetching referred pages for {} with a depth of {} with include pattern of {} with exclude pattern of {} with {} items",
                   rootname, depth, includePatternStr, excludePatternStr, columns );

        //
        // do the actual work
        //
        final String href  = context.getViewURL( rootname );
        final String title = "ReferredPagesPlugin: depth[" + depth +
                             "] include[" + includePatternStr + "] exclude[" + excludePatternStr +
                             "] format[" + ( formatCompact ? "compact" : "full" ) +
                             ( formatSort ? " sort" : "" ) + "]";

        if( items > 1 ) {
            result.append( "<div class=\"ReferredPagesPlugin\" style=\"" )
                    .append( "columns:" ).append( columns ).append( ";" )
                    .append( "moz-columns:" ).append( columns ).append( ";" )
                    .append( "webkit-columns:" ).append( columns ).append( ";" )
                    .append( "\">\n" );
        } else {
            result.append( "<div class=\"ReferredPagesPlugin\">\n" );
        }
        result.append( "<a class=\"wikipage\" href=\"" )
                .append( href ).append( "\" title=\"" )
                .append( TextUtil.replaceEntities( title ) )
                .append( "\">" )
                .append( TextUtil.replaceEntities( rootname ) )
                .append( "</a>\n" );
        exists.add( rootname );

        // pre compile all needed patterns
        // glob compiler :  * is 0..n instance of any char  -- more convenient as input
        // perl5 compiler : .* is 0..n instances of any char -- more powerful
        //PatternCompiler g_compiler = new GlobCompiler();

        try {
            includePattern = Pattern.compile( includePatternStr );
            excludePattern = Pattern.compile( excludePatternStr );
        } catch( final PatternSyntaxException e ) {
            if( includePattern == null ) {
                throw new PluginException( "Illegal include pattern detected." );
            } else if( excludePattern == null ) {
                throw new PluginException( "Illegal exclude pattern detected." );
            } else {
                throw new PluginException( "Illegal internal pattern detected." );
            }
        }

        // go get all referred links
        getReferredPages(context,rootname, 0);

        // close and finish
        result.append ("</div>\n" ) ;

        return result.toString() ;
    }

    /**
     * Retrieves a list of all referred pages. Is called recursively depending on the depth parameter.
     */
    private void getReferredPages( final Context context, final String pagename, int depth ) {
        if( depth >= this.depth ) {
            return;  // end of recursion
        }
        if( pagename == null ) {
            return;
        }
        if( !engine.getManager( PageManager.class ).wikiPageExists(pagename) ) {
            return;
        }

        final ReferenceManager mgr = engine.getManager( ReferenceManager.class );
        final Collection< String > allPages = mgr.findRefersTo( pagename );
        handleLinks( context, allPages, ++depth, pagename );
    }

    private void handleLinks( final Context context, final Collection<String> links, final int depth, final String pagename ) {
        boolean isUL = false;
        final var localLinkSet = new HashSet< String >();  // needed to skip multiple links to the same page
        localLinkSet.add( pagename );

        final var allLinks = new ArrayList< String >();

        allLinks.addAll( links );

        if( formatSort ) context.getEngine().getManager( PageManager.class ).getPageSorter().sort( allLinks );

        for( final String link : allLinks ) {
            if( localLinkSet.contains( link ) ) {
                continue; // skip multiple links to the same page
            }
            localLinkSet.add( link );

            if( !engine.getManager( PageManager.class ).wikiPageExists( link ) ) {
                continue; // hide links to non-existing pages
            }
            if( excludePattern.matcher( link ).matches() ) {
                continue;
            }
            if( !includePattern.matcher( link ).matches() ) {
                continue;
            }

            if( exists.contains( link ) ) {
                if( !formatCompact ) {
                    if( !isUL ) {
                        isUL = true;
                        result.append("<ul>\n");
                    }

                    //See https://www.w3.org/wiki/HTML_lists  for proper nesting of UL and LI
                    result.append( "<li> " ).append( TextUtil.replaceEntities( link ) ).append( "\n" );
                    getReferredPages( context, link, depth );  // added recursive call - on general request
                    result.append( "\n</li>\n" );
                }
            } else {
                if( !isUL ) {
                    isUL = true;
                    result.append("<ul>\n");
                }

                final String href = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), link );
                result.append( "<li><a class=\"wikipage\" href=\"" ).append( href ).append( "\">" ).append( TextUtil.replaceEntities( link ) ).append( "</a>\n" );
                exists.add( link );
                getReferredPages( context, link, depth );
                result.append( "\n</li>\n" );
            }
        }

        if( isUL ) {
            result.append("</ul>\n");
        }
    }

}