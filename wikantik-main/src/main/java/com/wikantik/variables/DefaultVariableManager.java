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
package com.wikantik.variables;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.Release;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.NoSuchVariableException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.modules.InternalModule;
import com.wikantik.pages.PageManager;
import com.wikantik.preferences.Preferences;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.stream.Collectors;


/**
 *  Manages variables.  Variables are case-insensitive.  A list of all available variables is on a Wiki page called "WikiVariables".
 *
 *  @since 1.9.20.
 */
public class DefaultVariableManager implements VariableManager {

    private static final Logger LOG = LogManager.getLogger( DefaultVariableManager.class );

    // Injected managers — null when using the default public constructor (managers are resolved
    // from context.getEngine() at call time). Set via the package-private test constructor.
    private final com.wikantik.api.managers.PageManager pageManager;
    private final com.wikantik.api.managers.AttachmentManager attachmentManager;
    private final FilterManager filterManager;

    /**
     *  Contains a list of those properties that shall never be shown. Put names here in lower case.
     */
    static final String[] THE_BIG_NO_NO_LIST = {
        "wikantik.auth.masterpassword"
    };

    /**
     * A resolver in the Chain of Responsibility that attempts to resolve a wiki variable from
     * a single source. Each resolver returns the value if found, or {@code null} to delegate
     * to the next resolver in the chain.
     */
    @FunctionalInterface
    interface VariableResolver {
        /**
         * Attempts to resolve the given variable name.
         *
         * @param lowerName the lower-cased variable name (used by the system variables resolver for reflection)
         * @param originalName the original variable name as supplied by the caller (used by all other resolvers
         *                     for case-sensitive lookups on context, session, page attributes, etc.)
         * @param context the current wiki context
         * @return the resolved value, or {@code null} if this resolver cannot handle it
         */
        String resolve( String lowerName, String originalName, Context context );
    }

    /**
     * The ordered chain of variable resolvers. Each resolver is tried in sequence; the first
     * non-null result wins. The resolution order is:
     * <ol>
     *   <li><b>System variables</b> — reflection on {@link SystemVariables} (getXxx methods)</li>
     *   <li><b>Context variables</b> — {@code context.getVariable(varName)}</li>
     *   <li><b>Session attributes</b> — {@code session.getAttribute(varName)}</li>
     *   <li><b>Request parameters</b> — {@code context.getHttpParameter(varName)}</li>
     *   <li><b>Page attributes</b> — {@code context.getPage().getAttribute(varName)}</li>
     *   <li><b>Real page attributes</b> — {@code context.getRealPage().getAttribute(varName)}</li>
     *   <li><b>Wiki properties</b> — {@code engine.getWikiProperties()} (only for "wikantik." prefix)</li>
     *   <li><b>Default empty values</b> — returns "" for the well-known "error" and "msg" variables</li>
     * </ol>
     */
    private final List<VariableResolver> resolverChain;

    /**
     *  Creates a VariableManager object using the property list given.
     *  The constructor builds the Chain of Responsibility resolver list that
     *  {@link #getValue(Context, String)} iterates over.
     *
     *  @param props The properties.
     */
    public DefaultVariableManager( final Properties props ) {
        this( props, null, null, null );
    }

    /**
     * Package-private constructor for testing.  Accepts pre-built manager instances so that
     * tests can inject mocks without starting a full wiki engine.  Pass {@code null} for any
     * manager to fall back to the normal {@code context.getEngine().getManager(...)} lookup.
     *
     * @param props              wiki properties (may be empty for most tests)
     * @param pageManager        optional injected PageManager
     * @param attachmentManager  optional injected AttachmentManager
     * @param filterManager      optional injected FilterManager
     */
    DefaultVariableManager( final Properties props,
                            final com.wikantik.api.managers.PageManager pageManager,
                            final com.wikantik.api.managers.AttachmentManager attachmentManager,
                            final FilterManager filterManager ) {
        this.pageManager = pageManager;
        this.attachmentManager = attachmentManager;
        this.filterManager = filterManager;

        final List<VariableResolver> chain = new ArrayList<>();

        // 1. System variables — reflection on SystemVariables (getXxx methods).
        //    Uses the lower-cased name for method lookup (variables are case-insensitive).
        chain.add( ( lowerName, originalName, context ) -> {
            try {
                final SystemVariables sysvars = new SystemVariables( context, this.pageManager, this.attachmentManager, this.filterManager );
                final String methodName = "get" + Character.toUpperCase( lowerName.charAt( 0 ) ) + lowerName.substring( 1 );
                final Method method = sysvars.getClass().getMethod( methodName );
                return ( String ) method.invoke( sysvars );
            } catch( final NoSuchMethodException e ) {
                return null;
            } catch( final Exception e ) {
                LOG.info( "Interesting exception: cannot fetch variable value", e );
                return "";
            }
        } );

        // 2. Context variables — context.getVariable(varName).
        //    Uses the original name for case-sensitive context lookup.
        chain.add( ( lowerName, originalName, context ) -> {
            final Object val = context.getVariable( originalName );
            return val != null ? val.toString() : null;
        } );

        // 3. Session attributes — session.getAttribute(varName)
        // 4. Request parameters — context.getHttpParameter(varName)
        //    These two share a ClassCastException guard so they are combined in one resolver.
        //    Uses the original name for case-sensitive lookups.
        chain.add( ( lowerName, originalName, context ) -> {
            final HttpServletRequest req = context.getHttpRequest();
            if( req != null && req.getSession() != null ) {
                final HttpSession session = req.getSession();
                try {
                    final String sessionAttribute = ( String ) session.getAttribute( originalName );
                    if( sessionAttribute != null ) {
                        return sessionAttribute;
                    }

                    final String httpParameter = context.getHttpParameter( originalName );
                    if( httpParameter != null ) {
                        return httpParameter;
                    }
                } catch( final ClassCastException e ) {
                    LOG.debug( "Not a String: {}", originalName );
                }
            }
            return null;
        } );

        // 5. Page attributes — context.getPage().getAttribute(varName).
        //    Uses the original name for case-sensitive attribute lookup.
        chain.add( ( lowerName, originalName, context ) -> {
            final Page pg = context.getPage();
            if( pg != null ) {
                final Object metadata = pg.getAttribute( originalName );
                if( metadata != null ) {
                    return metadataToString( metadata );
                }
            }
            return null;
        } );

        // 6. Real page attributes — context.getRealPage().getAttribute(varName).
        //    Uses the original name for case-sensitive attribute lookup.
        chain.add( ( lowerName, originalName, context ) -> {
            final Page rpg = context.getRealPage();
            if( rpg != null ) {
                final Object metadata = rpg.getAttribute( originalName );
                if( metadata != null ) {
                    return metadataToString( metadata );
                }
            }
            return null;
        } );

        // 7. Wiki properties — engine.getWikiProperties() (only for "wikantik." prefix).
        //    Uses the original name for property lookup (preserves original casing behavior).
        chain.add( ( lowerName, originalName, context ) -> {
            if( originalName.startsWith( "wikantik." ) ) {
                final Properties wikiProps = context.getEngine().getWikiProperties();
                final String propertyValue = wikiProps.getProperty( originalName );
                if( propertyValue != null ) {
                    return propertyValue;
                }
            }
            return null;
        } );

        // 8. Default empty values for well-known variables ("error", "msg").
        //    Uses the original name to preserve the original case-sensitive comparison.
        chain.add( ( lowerName, originalName, context ) -> {
            if( originalName.equals( VAR_ERROR ) || originalName.equals( VAR_MSG ) ) {
                return "";
            }
            return null;
        } );

        this.resolverChain = Collections.unmodifiableList( chain );
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public String parseAndGetValue( final Context context, final String link ) throws IllegalArgumentException, NoSuchVariableException {
        if( !link.startsWith( "{$" ) ) {
            throw new IllegalArgumentException( "Link does not start with {$" );
        }
        if( !link.endsWith( "}" ) ) {
            throw new IllegalArgumentException( "Link does not end with }" );
        }
        final String varName = link.substring( 2, link.length() - 1 );

        return getValue( context, varName.trim() );
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    // FIXME: somewhat slow.
    public String expandVariables( final Context context, final String source ) {
        final StringBuilder result = new StringBuilder();
        for( int i = 0; i < source.length(); i++ ) {
            if( source.charAt(i) == '{' ) {
                if( i < source.length()-2 && source.charAt(i+1) == '$' ) {
                    final int end = source.indexOf( '}', i );

                    if( end != -1 ) {
                        final String varname = source.substring( i+2, end );
                        String value;

                        try {
                            value = getValue( context, varname );
                        } catch( final NoSuchVariableException | IllegalArgumentException e ) {
                            value = e.getMessage();
                        }

                        result.append( value );
                        i = end;
                    }
                } else {
                    result.append( '{' );
                }
            } else {
                result.append( source.charAt(i) );
            }
        }

        return result.toString();
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public String getValue( final Context context, final String varName, final String defValue ) {
        try {
            return getValue( context, varName );
        } catch( final NoSuchVariableException e ) {
            return defValue;
        }
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public String getVariable( final Context context, final String name ) {
        return getValue( context, name, null );
    }

    /**
     * Resolves a wiki variable by iterating through the Chain of Responsibility.
     * <p>
     * The resolver chain is tried in order; the first resolver that returns a non-null
     * value wins. If no resolver can handle the variable, a {@link NoSuchVariableException}
     * is thrown. See the {@link #resolverChain} field documentation for the full resolution order.
     *
     * {@inheritDoc}
     */
    @Override
    public String getValue( final Context context, final String varName ) throws IllegalArgumentException, NoSuchVariableException {
        if( varName == null ) {
            throw new IllegalArgumentException( "Null variable name." );
        }
        if( varName.isEmpty() ) {
            throw new IllegalArgumentException( "Zero length variable name." );
        }
        // Faster than doing equalsIgnoreCase()
        final String name = varName.toLowerCase();

        for( final String prohibited : THE_BIG_NO_NO_LIST ) {
            if( name.equals( prohibited ) ) {
                return ""; // FIXME: Should this be something different?
            }
        }

        for( final VariableResolver resolver : resolverChain ) {
            final String value = resolver.resolve( name, varName, context );
            if( value != null ) {
                return value;
            }
        }

        throw new NoSuchVariableException( "No variable " + varName + " defined." );
    }

    /**
     * Converts a page metadata value to a string suitable for JSTL/EL use.
     * Handles Date (ISO format) and List (comma-separated) values that
     * SnakeYAML produces when parsing YAML frontmatter.
     */
    private static String metadataToString( final Object value ) {
        if ( value instanceof Date date ) {
            return new java.text.SimpleDateFormat( "yyyy-MM-dd" ).format( date );
        }
        if ( value instanceof List< ? > list ) {
            return list.stream()
                    .map( Object::toString )
                    .collect( Collectors.joining( ", " ) );
        }
        return value.toString();
    }

    /**
     *  This class provides the implementation for the different system variables.
     *  It is called via Reflection - any access to a variable called $xxx is mapped
     *  to getXxx() on this class.
     *  <p>
     *  This is a lot neater than using a huge if-else if branching structure
     *  that we used to have before.
     *  <p>
     *  Note that since we are case insensitive for variables, and VariableManager
     *  calls var.toLowerCase(), the getters for the variables do not have
     *  capitalization anywhere.  This may look a bit odd, but then again, this
     *  is not meant to be a public class.
     *
     *  @since 2.7.0
     */
    @SuppressWarnings( "unused" )
    private static class SystemVariables {

        private final Context context;
        private final com.wikantik.api.managers.PageManager pageManager;
        private final com.wikantik.api.managers.AttachmentManager attachmentManager;
        private final FilterManager filterManager;

        /** Standard constructor — managers resolved from engine at call time. */
        public SystemVariables( final Context ctx )
        {
            this( ctx, null, null, null );
        }

        /**
         * Test-friendly constructor — accepts pre-built manager instances.
         * A {@code null} manager falls back to {@code context.getEngine().getManager(...)}.
         */
        SystemVariables( final Context ctx,
                         final com.wikantik.api.managers.PageManager pageManager,
                         final com.wikantik.api.managers.AttachmentManager attachmentManager,
                         final FilterManager filterManager ) {
            this.context = ctx;
            this.pageManager = pageManager;
            this.attachmentManager = attachmentManager;
            this.filterManager = filterManager;
        }

        private com.wikantik.api.managers.PageManager pageManager() {
            return pageManager != null ? pageManager : context.getEngine().getManager( PageManager.class );
        }

        private com.wikantik.api.managers.AttachmentManager attachmentManager() {
            return attachmentManager != null ? attachmentManager : context.getEngine().getManager( AttachmentManager.class );
        }

        private FilterManager filterManager() {
            return filterManager != null ? filterManager : context.getEngine().getManager( FilterManager.class );
        }

        public String getPagename()
        {
            return context.getPage().getName();
        }

        public String getApplicationname()
        {
            return context.getEngine().getApplicationName();
        }

        public String getJspwikiversion()
        {
            return Release.getVersionString();
        }

        public String getEncoding() {
            return context.getEngine().getContentEncoding().displayName();
        }

        public String getTotalpages() {
            return Integer.toString( pageManager().getTotalPageCount() );
        }

        public String getPageprovider() {
            return pageManager().getCurrentProvider();
        }

        public String getPageproviderdescription() {
            return pageManager().getProviderDescription();
        }

        public String getAttachmentprovider() {
            final WikiProvider attachmentProvider = attachmentManager().getCurrentProvider();
            return (attachmentProvider != null) ? attachmentProvider.getClass().getName() : "-";
        }

        public String getAttachmentproviderdescription() {
            final WikiProvider attachmentProvider = attachmentManager().getCurrentProvider();
            return (attachmentProvider != null) ? attachmentProvider.getProviderInfo() : "-";
        }

        public String getInterwikilinks() {
            final var links = context.getEngine().getAllInterWikiLinks();
            if ( links.isEmpty() ) {
                return "(none configured)";
            }
            final StringBuilder sb = new StringBuilder( "<table class=\"wikitable\">" );
            sb.append( "<tr><th>Name</th><th>URL Pattern</th></tr>" );
            for ( final String name : links.stream().sorted().collect( Collectors.toList() ) ) {
                final String url = context.getEngine().getInterWikiURL( name );
                sb.append( "<tr><td><code>" ).append( name ).append( "</code></td><td><code>" )
                  .append( url != null ? url : "" ).append( "</code></td></tr>" );
            }
            sb.append( "</table>" );
            return sb.toString();
        }

        public String getInlinedimages() {

            return context.getEngine().getAllInlinedImagePatterns().stream().collect(Collectors.joining(", "));
        }

        public String getPluginpath() {
            final String pluginSearchPath = context.getEngine().getPluginSearchPath();

            return ( pluginSearchPath == null ) ? "-" : pluginSearchPath;
        }

        public String getBaseurl()
        {
            return context.getEngine().getBaseURL();
        }

        public String getUptime() {
            final Date now = new Date();
            long secondsRunning = ( now.getTime() - context.getEngine().getStartTime().getTime() ) / 1_000L;

            final long seconds = secondsRunning % 60;
            secondsRunning /= 60;
            final long minutes = secondsRunning % 60;
            secondsRunning /= 60;
            final long hours = secondsRunning % 24;
            final long days = secondsRunning / 24;

            return days + "d, " + hours + "h " + minutes + "m " + seconds + "s";
        }

        public String getLoginstatus() {
            final Session session = context.getWikiSession();
            return Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE ).getString( "varmgr." + session.getStatus() );
        }

        public String getUsername() {
            final Principal wup = context.getCurrentUser();
            final ResourceBundle rb = Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE );
            return wup != null ? wup.getName() : rb.getString( "varmgr.not.logged.in" );
        }

        public String getRequestcontext()
        {
            return context.getRequestContext();
        }

        public String getPagefilters() {
            final FilterManager fm = filterManager();
            final List< PageFilter > filters = fm.getFilterList();
            final StringBuilder sb = new StringBuilder();
            for( final PageFilter pf : filters ) {
                final String filterClassName = pf.getClass().getName();
                if( pf instanceof InternalModule im ) {
                    continue;
                }

                if( sb.length() > 0 ) {
                    sb.append( ", " );
                }
                sb.append( filterClassName );
            }
            return sb.toString();
        }
    }

}
