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
import com.wikantik.InternalWikiException;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.filters.FilterManager;
import com.wikantik.pages.PageManager;
import com.wikantik.parser.Heading;
import com.wikantik.parser.HeadingListener;
import com.wikantik.parser.MarkupParser;
import com.wikantik.preferences.Preferences;
import com.wikantik.render.RenderingManager;
import com.wikantik.util.TextUtil;
import com.wikantik.variables.VariableManager;

import java.io.IOException;
import java.util.Map;
import java.util.ResourceBundle;

/**
 *  Provides a table of contents.
 *  <p>Parameters : </p>
 *  <ul>
 *  <li><b>title</b> - The title of the table of contents.</li>
 *  <li><b>numbered</b> - if true, generates automatically numbers for the headings.</li>
 *  <li><b>start</b> - If using a numbered list, sets the start number.</li>
 *  <li><b>prefix</b> - If using a numbered list, sets the prefix used for the list.</li>
 *  </ul>
 *
 *  @since 2.2
 */
public class TableOfContents implements Plugin, HeadingListener {

    private static final Logger LOG = LogManager.getLogger( TableOfContents.class );

    /** Parameter name for setting the title. */
    public static final String PARAM_TITLE = "title";

    /** Parameter name for setting whether the headings should be numbered. */
    public static final String PARAM_NUMBERED = "numbered";

    /** Parameter name for setting where the numbering should start. */
    public static final String PARAM_START = "start";

    /** Parameter name for setting what the prefix for the heading is. */
    public static final String PARAM_PREFIX = "prefix";

    private static final String VAR_ALREADY_PROCESSING = "__TableOfContents.processing";

    final StringBuilder buf = new StringBuilder();
    private boolean usingNumberedList;
    private String prefix = "";
    private int starting;
    private int level1Index;
    private int level2Index;
    private int level3Index;
    private int lastLevel;

    /**
     *  {@inheritDoc}
     */
    @Override
    public void headingAdded( final Context context, final Heading hd ) {
        LOG.debug( "HD: {}, {}, {}", hd.level, hd.titleText, hd.titleAnchor );

        switch( hd.level ) {
          case Heading.HEADING_SMALL:
            buf.append("<li class=\"toclevel-3\">");
            level3Index++;
            break;
          case Heading.HEADING_MEDIUM:
            buf.append("<li class=\"toclevel-2\">");
            level2Index++;
            break;
          case Heading.HEADING_LARGE:
            buf.append("<li class=\"toclevel-1\">");
            level1Index++;
            break;
          default:
            throw new InternalWikiException("Unknown depth in toc! (Please submit a bug report.)");
        }

        if( level1Index < starting ) {
            // in case we never had a large heading ...
            level1Index++;
        }
        if( ( lastLevel == Heading.HEADING_SMALL ) && ( hd.level != Heading.HEADING_SMALL ) ) {
            level3Index = 0;
        }
        if( ( ( lastLevel == Heading.HEADING_SMALL ) || ( lastLevel == Heading.HEADING_MEDIUM ) ) && ( hd.level
                == Heading.HEADING_LARGE ) ) {
            level3Index = 0;
            level2Index = 0;
        }

        final String titleSection = hd.titleSection.replace( '%', '_' );
        final String pageName = context.getEngine().encodeName(context.getPage().getName()).replace( '%', '_' );

        final String sectref = "#section-"+pageName+"-"+titleSection;

        buf.append( "<a class=\"wikipage\" href=\"" ).append( sectref ).append( "\">" );
        if (usingNumberedList)
        {
            switch( hd.level )
            {
            case Heading.HEADING_SMALL:
                buf.append( prefix ).append( level1Index ).append( "." ).append( level2Index ).append( "." ).append( level3Index ).append( " " );
                break;
            case Heading.HEADING_MEDIUM:
                buf.append( prefix ).append( level1Index ).append( "." ).append( level2Index ).append( " " );
                break;
            case Heading.HEADING_LARGE:
                buf.append( prefix ).append( level1Index ).append( " " );
                break;
            default:
                throw new InternalWikiException("Unknown depth in toc! (Please submit a bug report.)");
            }
        }
        buf.append( TextUtil.replaceEntities( hd.titleText ) ).append( "</a></li>\n" );

        lastLevel = hd.level;
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public String execute( final Context context, final Map< String, String > params ) throws PluginException {
        final Engine engine = context.getEngine();
        final Page page = context.getPage();
        final ResourceBundle rb = Preferences.getBundle( context, Plugin.CORE_PLUGINS_RESOURCEBUNDLE );

        if( context.getVariable( VAR_ALREADY_PROCESSING ) != null ) {
            //return rb.getString("tableofcontents.title");
            return "<a href=\"#section-TOC\" class=\"toc\">"+rb.getString("tableofcontents.title")+"</a>";
        }

        final StringBuilder sb = new StringBuilder();

        sb.append("<div class=\"toc\">\n");
        sb.append("<div class=\"collapsebox\">\n");

        final String title = params.get(PARAM_TITLE);
        sb.append("<h4 id=\"section-TOC\">");
        if( title != null ) {
            sb.append( TextUtil.replaceEntities( title ) );
        } else {
            sb.append( rb.getString( "tableofcontents.title" ) );
        }
        sb.append( "</h4>\n" );

        // should we use an ordered list?
        usingNumberedList = false;
        if( params.containsKey( PARAM_NUMBERED ) ) {
            final String numbered = params.get( PARAM_NUMBERED );
            if( numbered.equalsIgnoreCase( "true" ) ) {
                usingNumberedList = true;
            } else if( numbered.equalsIgnoreCase( "yes" ) ) {
                usingNumberedList = true;
            }
        }

        // if we are using a numbered list, get the rest of the parameters (if any) ...
        if (usingNumberedList) {
            int start = 0;
            final String startStr = params.get(PARAM_START);
            if( ( startStr != null ) && ( startStr.matches( "^\\d+$" ) ) ) {
                start = Integer.parseInt(startStr);
            }
            if (start < 0) start = 0;

            starting = start;
            level1Index = start - 1;
            if (level1Index < 0) level1Index = 0;
            level2Index = 0;
            level3Index = 0;
            prefix = TextUtil.replaceEntities( params.get(PARAM_PREFIX) );
            if (prefix == null) prefix = "";
            lastLevel = Heading.HEADING_LARGE;
        }

        try {
            String wikiText = engine.getManager( PageManager.class ).getPureText( page );
            final boolean runFilters = "true".equals( engine.getManager( VariableManager.class ).getValue( context, VariableManager.VAR_RUNFILTERS, "true" ) );

            if( runFilters ) {
				try {
					final FilterManager fm = engine.getManager( FilterManager.class );
					wikiText = fm.doPreTranslateFiltering(context, wikiText);

				} catch( final Exception e ) {
					LOG.error("Could not construct table of contents: Filter Error", e);
					throw new PluginException("Unable to construct table of contents (see logs)");
				}
            }

            context.setVariable( VAR_ALREADY_PROCESSING, "x" );

            final MarkupParser parser = engine.getManager( RenderingManager.class ).getParser( context, wikiText );
            parser.addHeadingListener( this );
            parser.parse();

            sb.append( "<ul>\n" ).append( buf ).append( "</ul>\n" );
        } catch( final IOException e ) {
            LOG.error("Could not construct table of contents", e);
            throw new PluginException("Unable to construct table of contents (see logs)");
        }

        sb.append("</div>\n</div>\n");

        return sb.toString();
    }

}
