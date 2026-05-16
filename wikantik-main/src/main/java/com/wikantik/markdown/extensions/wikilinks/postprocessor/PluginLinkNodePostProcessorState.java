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
package com.wikantik.markdown.extensions.wikilinks.postprocessor;

import com.vladsch.flexmark.ext.toc.TocBlock;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;
import com.vladsch.flexmark.util.sequence.CharSubSequence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.parser.PluginContent;
import com.wikantik.preferences.Preferences;
import com.wikantik.util.TextUtil;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.Map;
import java.util.ResourceBundle;


/**
 * {@link NodePostProcessorState} which further post processes plugin links.
 */
public class PluginLinkNodePostProcessorState implements NodePostProcessorState< WikantikLink > {

    private static final Logger LOG = LogManager.getLogger( PluginLinkNodePostProcessorState.class );
    private final Context wikiContext;
    private final boolean wysiwygEditorMode;

    public PluginLinkNodePostProcessorState( final Context wikiContext ) {
        this.wikiContext = wikiContext;
        final Boolean wysiwygVariable = wikiContext.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE );
        wysiwygEditorMode = wysiwygVariable != null ? wysiwygVariable : false;
    }

    /**
     * {@inheritDoc}
     *
     * @see NodePostProcessorState#process(NodeTracker, Node) 
     */
    @Override
    public void process( final NodeTracker state, final WikantikLink link ) {
        if( link.getText().toString().startsWith( "{TableOfContents" ) ) {
            handleTableOfContentsPlugin( state, link );
            return;
        }
        PluginContent pluginContent = null;
        try {
            pluginContent = PluginContent.parsePluginLine( wikiContext, link.getUrl().toString(), -1 ); // -1 == do not generate _bounds parameter
            //
            //  This might sometimes fail, especially if there is something which looks
            //  like a plugin invocation but is really not.
            //
            if( pluginContent != null ) {
                final String pluginInvocation = pluginInvocation( link.getText().toString(), pluginContent );
                final WikiHtmlInline content = WikiHtmlInline.of( pluginInvocation );
                pluginContent.executeParse( wikiContext );
                NodePostProcessorStateCommonOperations.addContent( state, link, content );
            }
        } catch( final PluginException e ) {
            LOG.info( "{} : {} - Failed to insert plugin: {}", wikiContext.getRealPage().getWiki(), wikiContext.getRealPage().getName(), e.getMessage() );
            if( !wysiwygEditorMode ) {
                final ResourceBundle rbPlugin = Preferences.getBundle( wikiContext, Plugin.CORE_PLUGINS_RESOURCEBUNDLE );
                NodePostProcessorStateCommonOperations.makeError( state, link, MessageFormat.format( rbPlugin.getString( "plugin.error.insertionfailed" ),
                                                                                                                         wikiContext.getRealPage().getWiki(),
                                                                                                                         wikiContext.getRealPage().getName(),
                                                                                                                         e.getMessage() ) );
            }
        } finally {
            if( pluginContent != null ) {
                removeLink( state, link );
            }
        }
    }

    /**
     * Return plugin execution. As plugin execution may not fire the plugin (i.e., on WYSIWYG editors), on those cases, the plugin line is returned.
     *
     * @param pluginMarkup plugin markup line
     * @param pluginContent the plugin content.
     * @return plugin execution, or plugin markup line if it wasn't executed.
     */
    String pluginInvocation( final String pluginMarkup, final PluginContent pluginContent ) {
        final String pluginInvocation = pluginContent.invoke( wikiContext );
        if( pluginMarkup.equals( pluginInvocation + "()" ) ) { // plugin line markup == plugin execution + "()" -> hasn't been executed
            return pluginMarkup;
        } else {
            return pluginInvocation;
        }
    }

    void handleTableOfContentsPlugin(final NodeTracker state, final WikantikLink link) {
        if( !wysiwygEditorMode ) {
            final ResourceBundle rb = Preferences.getBundle( wikiContext, Plugin.CORE_PLUGINS_RESOURCEBUNDLE );

            final Map< String, String > params = parseTocParameters( link );
            final String customTitle = params.get( "title" );
            final String titleText = customTitle != null ? TextUtil.replaceEntities( customTitle )
                                                          : rb.getString( "tableofcontents.title" );
            final String numbered = params.get( "numbered" );
            final boolean isNumbered = "true".equalsIgnoreCase( numbered ) || "yes".equalsIgnoreCase( numbered );
            final String tocOptions = isNumbered ? "levels=1-3 numbered" : "levels=1-3";

            final WikiHtmlInline divToc = WikiHtmlInline.of( "<div class=\"toc\">\n" );
            final WikiHtmlInline divCollapseBox = WikiHtmlInline.of( "<div class=\"collapsebox\">\n" );
            final WikiHtmlInline divsClosing = WikiHtmlInline.of( "</div>\n</div>\n" );
            final WikiHtmlInline h4Title = WikiHtmlInline.of( "<h4 id=\"section-TOC\">" + titleText + "</h4>\n" );
            final TocBlock toc = new TocBlock( CharSubSequence.of( "[TOC]" ), CharSubSequence.of( tocOptions ) );

            link.insertAfter( divToc );
            divToc.insertAfter( divCollapseBox );
            divCollapseBox.insertAfter( h4Title );
            h4Title.insertAfter( toc );
            toc.insertAfter( divsClosing );

        } else {
            NodePostProcessorStateCommonOperations.inlineLinkTextOnWysiwyg( state, link, wysiwygEditorMode );
        }
        removeLink( state, link );
    }

    /**
     * Parses the parameters of a {@code [{TableOfContents ...}]} markup link.
     * Returns an empty map (and logs a warning) if parsing fails, so the TOC
     * still renders with default options rather than throwing.
     *
     * @param link the TableOfContents plugin link node.
     * @return the parsed parameter map, never {@code null}.
     */
    private Map< String, String > parseTocParameters( final WikantikLink link ) {
        final String markup = link.getUrl().toString();
        try {
            final PluginContent pc = PluginContent.parsePluginLine( wikiContext, markup, -1 );
            if( pc != null ) {
                return pc.getParameters();
            }
        } catch( final PluginException e ) {
            LOG.warn( "Could not parse TableOfContents parameters from '{}'; rendering TOC with defaults",
                      markup, e );
        }
        return Collections.emptyMap();
    }

    void removeLink(final NodeTracker state, final WikantikLink link) {
        link.unlink();
        state.nodeRemoved( link );
    }

}
