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

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Plugin that renders the set of all pages belonging to a named Hub.
 *
 * <p>Parameters:
 * <ul>
 *   <li><b>hub</b> (required) — Hub page name</li>
 *   <li><b>max</b> — maximum rows to output</li>
 *   <li><b>detail</b> — "links" (default) or "cards"</li>
 * </ul>
 * Plus all parameters inherited from {@link AbstractReferralPlugin}.
 *
 * <p>Usage: {@code [{HubSet hub='Technology' max='10' detail='cards'}]}</p>
 */
public class HubSetPlugin extends AbstractReferralPlugin {

    private static final Logger LOG = LogManager.getLogger( HubSetPlugin.class );

    public static final String PARAM_HUB    = "hub";
    public static final String PARAM_MAX    = "max";
    public static final String PARAM_DETAIL = "detail";

    @Override
    public String execute( final Context context, final Map< String, String > params ) throws PluginException {
        final String hubName = params.get( PARAM_HUB );
        if ( hubName == null || hubName.isBlank() ) {
            return "<div class=\"error\">HubSet plugin requires a 'hub' parameter.</div>";
        }

        final int max = TextUtil.parseIntParameter( params.get( PARAM_MAX ), ALL_ITEMS );
        final String detail = params.getOrDefault( PARAM_DETAIL, "links" );

        final PageManager pm = context.getEngine().getManager( PageManager.class );
        final Page hubPage = pm.getPage( hubName );
        if ( hubPage == null ) {
            return "<div class=\"error\">Hub '" + escapeHtml( hubName ) + "' does not exist.</div>";
        }

        final String hubContent = pm.getPureText( hubPage );
        final ParsedPage parsed = FrontmatterParser.parse( hubContent != null ? hubContent : "" );

        if ( !"hub".equals( parsed.metadata().get( "type" ) ) ) {
            return "<div class=\"error\">Page '" + escapeHtml( hubName ) + "' is not a Hub (type != hub).</div>";
        }

        final Object relatedObj = parsed.metadata().get( "related" );
        List< String > members = List.of();
        if ( relatedObj instanceof List< ? > list ) {
            members = list.stream()
                .filter( String.class::isInstance )
                .map( String.class::cast )
                .toList();
        }

        if ( members.isEmpty() ) {
            return "<div class=\"hub-set-empty\">Hub '" + escapeHtml( hubName ) + "' has no member pages.</div>";
        }

        // Apply inherited filtering (exclude/include patterns, system pages)
        super.initialize( context, params );
        final List< String > filtered = filterAndSortCollection( members );

        if ( filtered.isEmpty() ) {
            return "<div class=\"hub-set-empty\">Hub '" + escapeHtml( hubName ) + "' has no member pages.</div>";
        }

        // Apply max limit
        final List< String > limited = max > 0 && max < filtered.size()
            ? filtered.subList( 0, max ) : filtered;

        if ( "cards".equalsIgnoreCase( detail ) ) {
            return renderCards( limited, pm );
        }

        // Default: links mode — use inherited wikitizeCollection
        final String wikitext = wikitizeCollection( limited, separator, ALL_ITEMS );
        return makeHTML( context, wikitext );
    }

    private String renderCards( final List< String > pages, final PageManager pm ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "<div class=\"hub-set-cards\">" );

        for ( final String pageName : pages ) {
            sb.append( "<div class=\"hub-set-card\">" );
            sb.append( "<h4><a href=\"/" ).append( escapeHtml( pageName ) ).append( "\">" )
                .append( escapeHtml( pageName ) ).append( "</a></h4>" );

            final Page page = pm.getPage( pageName );
            if ( page != null ) {
                final String content = pm.getPureText( page );
                if ( content != null ) {
                    final ParsedPage parsed = FrontmatterParser.parse( content );
                    final Object summary = parsed.metadata().get( "summary" );
                    if ( summary instanceof String s && !s.isBlank() ) {
                        sb.append( "<p>" ).append( escapeHtml( s ) ).append( "</p>" );
                    }
                    final Object tags = parsed.metadata().get( "tags" );
                    if ( tags instanceof List< ? > tagList && !tagList.isEmpty() ) {
                        sb.append( "<div class=\"hub-set-tags\">" );
                        for ( final Object tag : tagList ) {
                            sb.append( "<span class=\"hub-set-tag\">" )
                                .append( escapeHtml( String.valueOf( tag ) ) )
                                .append( "</span> " );
                        }
                        sb.append( "</div>" );
                    }
                }
            }
            sb.append( "</div>" );
        }

        sb.append( "</div>" );
        return sb.toString();
    }

    private static String escapeHtml( final String s ) {
        return s.replace( "&", "&amp;" )
                .replace( "<", "&lt;" )
                .replace( ">", "&gt;" )
                .replace( "\"", "&quot;" );
    }
}
