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
package com.wikantik.parser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.InternalWikiException;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.ParserStagePlugin;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.api.plugin.PluginElement;
import com.wikantik.plugin.PluginManager;
import com.wikantik.preferences.Preferences;
import com.wikantik.variables.VariableManager;
import org.jdom2.Text;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ResourceBundle;
import java.util.regex.Matcher;


/**
 * Stores the contents of a plugin in a WikiDocument DOM tree.
 * <p/>
 * If the Context.VAR_WYSIWYG_EDITOR_MODE is set to Boolean.TRUE in the context, then the plugin is rendered as WikiMarkup.
 * This allows an HTML editor to work without rendering the plugin each time as well.
 * <p/>
 * If Context.VAR_EXECUTE_PLUGINS is set to Boolean.FALSE, then the plugin is not executed.
 *
 * @since 2.4
 */
public class PluginContent extends Text implements PluginElement {

    private static final String BLANK = "";
    private static final String CMDLINE = "_cmdline";
    private static final String ELEMENT_BR = "<br/>";
    private static final String EMITTABLE_PLUGINS = "Image|FormOpen|FormClose|FormInput|FormTextarea|FormSelect";
    private static final String LINEBREAK = "\n";
    private static final String PLUGIN_START = "[{";
    private static final String PLUGIN_END = "}]";
    private static final String SPACE = " ";

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger(PluginContent.class);

    private final String pluginName;
    private final Map< String, String > params;

    /**
     * Creates a new DOM element with the given plugin name and a map of parameters.
     *
     * @param pluginName The FQN of a plugin.
     * @param parameters A Map of parameters.
     */
    public PluginContent( final String pluginName, final Map< String, String > parameters) {
        this.pluginName = pluginName;
        params = parameters;
    }

    /**{@inheritDoc}*/
    @Override
    public String getPluginName() {
        return pluginName;
    }

    /**{@inheritDoc}*/
    @Override
    public String getParameter( final String name) {
        return params.get( name );
    }

    /**{@inheritDoc}*/
    @Override
    public Map< String, String > getParameters() {
        return params;
    }

    /**{@inheritDoc}*/
    @Override
    public String getValue() {
        return getText();
    }

    /**{@inheritDoc}*/
    @Override
    public String getText() {
        final WikiDocument doc = ( WikiDocument )getDocument();
        if( doc == null ) {
            //
            // This element has not yet been attached anywhere, so we simply assume there is no rendering and return the plugin name.
            // This is required e.g. when the paragraphify() checks whether the element is empty or not.  We can't of course know
            // whether the rendering would result in an empty string or not, but let us assume it does not.
            //
            return getPluginName();
        }

        final Context context = doc.getContext();
        if( context == null ) {
            LOG.info( "WikiContext garbage-collected, cannot proceed" );
            return getPluginName();
        }

        return invoke( context );
    }

    /**{@inheritDoc}*/
    @Override
    public String invoke( final Context context ) {
		String result;
		final Boolean wysiwygVariable = context.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE );
        boolean wysiwygEditorMode = false;
        if( wysiwygVariable != null ) {
            wysiwygEditorMode = wysiwygVariable;
        }

        try {
            //
            //  Determine whether we should emit the actual code for this plugin or
            //  whether we should execute it.  For some plugins we always execute it,
            //  since they can be edited visually.
            //
            // FIXME: The plugin name matching should not be done here, but in a per-editor resource
            if( wysiwygEditorMode && !pluginName.matches( EMITTABLE_PLUGINS ) ) {
                result = PLUGIN_START + pluginName + SPACE;

                // convert newlines to <br> in case the plugin has a body.
                final String cmdLine = params.get( CMDLINE ).replaceAll( LINEBREAK, ELEMENT_BR );
                result = result + cmdLine + PLUGIN_END;
            } else {
                final Boolean executePlugins = context.getVariable( Context.VAR_EXECUTE_PLUGINS );
                if (executePlugins != null && !executePlugins ) {
                    return BLANK;
                }

                final Engine engine = context.getEngine();
                final Map< String, String > parsedParams = new HashMap<>();

                //  Parse any variable instances from the string
                for( final Map.Entry< String, String > e : params.entrySet() ) {
                    String val = e.getValue();
                    val = engine.getManager( VariableManager.class).expandVariables( context, val );
                    parsedParams.put( e.getKey(), val );
                }
                final PluginManager pm = engine.getManager( PluginManager.class );
                result = pm.execute( context, pluginName, parsedParams );
            }
        } catch( final Exception e ) {
            if( wysiwygEditorMode ) {
                result = "";
            } else {
                // LOG.info("Failed to execute plugin",e);
                final ResourceBundle rb = Preferences.getBundle( context, Plugin.CORE_PLUGINS_RESOURCEBUNDLE );
                result = MarkupParser.makeError( MessageFormat.format( rb.getString( "plugin.error.insertionfailed" ), 
                		                                               context.getRealPage().getWiki(), 
                		                                               context.getRealPage().getName(), 
                		                                               e.getMessage() ) ).getText();
            }
        }

        return result;
	}

    /**{@inheritDoc}*/
    @Override
    public void executeParse( final Context context ) throws PluginException {
        final PluginManager pm = context.getEngine().getManager( PluginManager.class );
        if( pm.pluginsEnabled() ) {
            final ResourceBundle rb = Preferences.getBundle( context, Plugin.CORE_PLUGINS_RESOURCEBUNDLE);
            final Map< String, String > params = getParameters();
            final Plugin plugin = pm.newWikiPlugin( getPluginName(), rb );
            try {
                if( plugin instanceof ParserStagePlugin psp ) {
                    psp.executeParser(this, context, params );
                }
            } catch( final ClassCastException e ) {
                throw new PluginException( MessageFormat.format( rb.getString("plugin.error.notawikiplugin"), getPluginName() ), e );
            }
        }
    }

    /**
     * Parses a plugin invocation and returns a DOM element.
     *
     * @param context     The WikiContext
     * @param commandline The line to parse
     * @param pos         The position in the stream parsing.
     * @return A DOM element
     * @throws PluginException If plugin invocation is faulty
     * @since 2.10.0
     */
    public static PluginContent parsePluginLine( final Context context, final String commandline, final int pos ) throws PluginException {
        try {
            final PluginManager pm = context.getEngine().getManager( PluginManager.class );
            final Matcher matcher = pm.getPluginPattern().matcher( commandline );
            if( matcher.find() ) {
                final String plugin = matcher.group( 2 );
                final String args = commandline.substring( matcher.end(),
                                                           commandline.length() - ( commandline.charAt( commandline.length() - 1 ) == '}' ? 1 : 0 ) );
                final Map< String, String > arglist = pm.parseArgs( args );

                // set wikitext bounds of plugin as '_bounds' parameter, e.g., [345,396]
                if( pos != -1 ) {
                    final int end = pos + commandline.length() + 2;
                    final String bounds = pos + "|" + end;
                    arglist.put( PluginManager.PARAM_BOUNDS, bounds );
                }

                return new PluginContent( plugin, arglist );
            }
        } catch( final ClassCastException e ) {
            LOG.error( "Invalid type offered in parsing plugin arguments.", e );
            throw new InternalWikiException( "Oops, someone offered !String!", e );
        } catch( final NoSuchElementException e ) {
            final String msg = "Missing parameter in plugin definition: " + commandline;
            LOG.warn( msg, e );
            throw new PluginException( msg );
        } catch( final IOException e ) {
            final String msg = "Zyrf.  Problems with parsing arguments: " + commandline;
            LOG.warn( msg, e );
            throw new PluginException( msg );
        }

        return null;
    }

}
