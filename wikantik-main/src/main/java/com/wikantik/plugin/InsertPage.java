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
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.api.managers.PageManager;
import com.wikantik.preferences.Preferences;
import com.wikantik.render.RenderingManager;
import com.wikantik.util.HttpUtil;
import com.wikantik.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;


/**
 *  Inserts page contents.  Muchos thanks to Scott Hurlbert for the initial code.
 *
 *  <p>Parameters : </p>
 *  <ul>
 *  <li><b>page</b> - the name of the page to be inserted</li>
 *  <li><b>style</b> - the style to use</li>
 *  <li><b>maxlength</b> - the maximum length of the page to be inserted (page contents)</li>
 *  <li><b>class</b> - the class to use</li>
 *  <li><b>section</b> - the section of the page that has to be inserted (separated by "----"</li>
 *  <li><b>default</b> - the text to insert if the requested page does not exist</li>
 *  </ul>
 *
 *  @since 2.1.37
 */
public class InsertPage implements Plugin {

    /** Parameter name for setting the page.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_PAGENAME  = "page";
    /** Parameter name for setting the style.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_STYLE     = "style";
    /** Parameter name for setting the maxlength.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_MAXLENGTH = "maxlength";
    /** Parameter name for setting the class.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_CLASS     = "class";
    /** Parameter name for setting the show option.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_SHOW   = "show";
    /** Parameter name for setting the section.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_SECTION   = "section";
    /** Parameter name for setting the default.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_DEFAULT   = "default";

    private static final String DEFAULT_STYLE = "";

    private static final String ONCE_COOKIE = "JSPWiki.Once.";

    /** This attribute is stashed in the WikiContext to make sure that we don't have circular references. */
    public static final String ATTR_RECURSE    = "com.wikantik.plugin.InsertPage.recurseCheck";

    /**
     *  {@inheritDoc}
     */
    @Override
    public String execute( final Context context, final Map<String, String> params ) throws PluginException {
        final String includedPage = TextUtil.replaceEntities( params.get( PARAM_PAGENAME ) );
        if ( includedPage == null ) {
            return errorSpan( "You have to define a page!" );
        }

        final Engine engine = context.getEngine();
        final Page page;
        try {
            page = resolvePage( engine, includedPage );
        } catch ( final ProviderException e ) {
            return errorSpan( "Page could not be found by the page provider." );
        }

        if ( page == null ) {
            return missingPageMarkup( context, includedPage, params.get( PARAM_DEFAULT ) );
        }

        final List< String > recursionStack = currentRecursionStack( context );
        if ( recursionStack.contains( page.getName() ) ) {
            return errorSpan( "Error: Circular reference - you can't include a page in itself!" );
        }

        if ( !hasViewPermission( engine, context, page ) ) {
            return errorSpan( "You do not have permission to view this included page." );
        }

        final boolean showOnce = "once".equals( params.get( PARAM_SHOW ) );
        final String cookieName = showOnce ? showOnceCookieName( page ) : "";
        if ( showOnce && HttpUtil.retrieveCookieValue( context.getHttpRequest(), cookieName ) != null ) {
            return "";  // silent exit
        }

        pushRecursionMarker( context, recursionStack, page );
        final String rendered = renderIncludedPage( engine, context, page, params, includedPage, showOnce, cookieName );
        popRecursionMarker( context, recursionStack, page );
        return rendered;
    }

    // ---- Guards and lookup helpers ----

    private static String errorSpan( final String message ) {
        return "<span class=\"error\">" + message + "</span>";
    }

    private static Page resolvePage( final Engine engine, final String includedPage ) throws ProviderException {
        final String pageName = engine.getFinalPageName( includedPage );
        return engine.getManager( PageManager.class ).getPage(
            Objects.requireNonNullElse( pageName, includedPage ) );
    }

    private static boolean hasViewPermission( final Engine engine, final Context context, final Page page ) {
        final AuthorizationManager mgr = engine.getManager( AuthorizationManager.class );
        return mgr.checkPermission( context.getWikiSession(), PermissionFactory.getPagePermission( page, "view" ) );
    }

    private static String showOnceCookieName( final Page page ) {
        return ONCE_COOKIE + TextUtil.urlEncodeUTF8( page.getName() ).replaceAll( "\\+", "%20" );
    }

    private static String missingPageMarkup( final Context context, final String includedPage, final String defaultstr ) {
        if ( defaultstr != null ) return defaultstr;
        final String editUrl = context.getURL( ContextEnum.PAGE_EDIT.getRequestContext(), includedPage );
        return "There is no page called '" + includedPage + "'.  Would you like to "
             + "<a href=\"" + editUrl + "\">create it?</a>";
    }

    // ---- Recursion stack management ----

    @SuppressWarnings( "unchecked" )
    private static List< String > currentRecursionStack( final Context context ) {
        final List< String > prior = context.getVariable( ATTR_RECURSE );
        return prior != null ? prior : new ArrayList<>();
    }

    private static void pushRecursionMarker( final Context context, final List< String > stack, final Page page ) {
        stack.add( page.getName() );
        context.setVariable( ATTR_RECURSE, stack );
    }

    private static void popRecursionMarker( final Context context, final List< String > stack, final Page page ) {
        stack.remove( page.getName() );
        context.setVariable( ATTR_RECURSE, stack );
    }

    // ---- Render ----

    private static String renderIncludedPage(
            final Engine engine,
            final Context context,
            final Page page,
            final Map< String, String > params,
            final String includedPage,
            final boolean showOnce,
            final String cookieName ) throws PluginException {
        final ResourceBundle rb = Preferences.getBundle( context, Plugin.CORE_PLUGINS_RESOURCEBUNDLE );
        final String clazz = TextUtil.replaceEntities( params.get( PARAM_CLASS ) );
        final String styleParam = TextUtil.replaceEntities( params.get( PARAM_STYLE ) );
        final String style = styleParam != null ? styleParam : DEFAULT_STYLE;
        final int section = TextUtil.parseIntParameter( params.get( PARAM_SECTION ), -1 );
        final int maxlenParam = TextUtil.parseIntParameter( params.get( PARAM_MAXLENGTH ), -1 );
        final int maxlen = maxlenParam == -1 ? Integer.MAX_VALUE : maxlenParam;

        final Context includedContext = context.clone();
        includedContext.setPage( page );

        String pageData = engine.getManager( PageManager.class ).getPureText( page );
        if ( section != -1 ) {
            try {
                pageData = TextUtil.getSection( pageData, section );
            } catch ( final IllegalArgumentException e ) {
                throw new PluginException( e.getMessage(), e );
            }
        }

        String moreLink = "";
        if ( pageData.length() > maxlen ) {
            pageData = pageData.substring( 0, maxlen ) + " ...";
            moreLink = "<p><a href=\""
                + context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), includedPage )
                + "\">" + rb.getString( "insertpage.more" ) + "</a></p>";
        }

        final StringBuilder res = new StringBuilder();
        res.append( "<div class=\"inserted-page " );
        if ( clazz != null ) res.append( clazz );
        if ( !DEFAULT_STYLE.equals( style ) ) res.append( "\" style=\"" ).append( style );
        if ( showOnce ) res.append( "\" data-once=\"" ).append( cookieName );
        res.append( "\" >" );
        res.append( engine.getManager( RenderingManager.class ).textToHTML( includedContext, pageData ) );
        res.append( moreLink );
        res.append( "</div>" );
        return res.toString();
    }

}
