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
package com.wikantik;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Command;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.pages.PageManager;
import com.wikantik.ui.CommandResolver;
import com.wikantik.ui.Installer;
import com.wikantik.ui.PageCommand;
import com.wikantik.ui.WikiCommand;
import com.wikantik.util.TextUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.PageContext;
import java.security.Permission;
import java.security.Principal;
import java.util.HashMap;
import java.util.PropertyPermission;

/**
 *  <p>Provides state information throughout the processing of a page.  A WikiContext is born when the JSP pages that are the main entry
 *  points, are invoked.  The JSPWiki engine creates the new WikiContext, which basically holds information about the page, the
 *  handling engine, and in which context (view, edit, etc) the call was done.</p>
 *  <p>A WikiContext also provides request-specific variables, which can be used to communicate between plugins on the same page, or
 *  between different instances of the same plugin.  A WikiContext variable is valid until the processing of the page has ended.  For
 *  an example, please see the Counter plugin.</p>
 *  <p>When a WikiContext is created, it automatically associates a {@link WikiSession} object with the user's HttpSession. The
 *  WikiSession contains information about the user's authentication status, and is consulted by {@link #getCurrentUser()} object.</p>
 *  <p>Do not cache the page object that you get from the WikiContext; always use getPage()!</p>
 *
 *  @see com.wikantik.plugin.Counter
 */
public class WikiContext implements Context, Command {

    private Command  command;
    private WikiPage page;
    private WikiPage realPage;
    private Engine   engine;
    private String   template = "default";

    private HashMap< String, Object > variableMap = new HashMap<>();

    /** Stores the HttpServletRequest.  May be null, if the request did not come from a servlet. */
    protected HttpServletRequest request;

    private Session session;

    /** User is doing administrative things. */
    public static final String ADMIN = ContextEnum.WIKI_ADMIN.getRequestContext();

    /** User is downloading an attachment. */
    public static final String ATTACH = ContextEnum.PAGE_ATTACH.getRequestContext();

    /** User is commenting something. */
    public static final String COMMENT = ContextEnum.PAGE_COMMENT.getRequestContext();

    /** User has an internal conflict, and does quite not know what to do. Please provide some counseling. */
    public static final String CONFLICT = ContextEnum.PAGE_CONFLICT.getRequestContext();

    /** User wishes to create a new group */
    public static final String CREATE_GROUP = ContextEnum.WIKI_CREATE_GROUP.getRequestContext();

    /** User is deleting a page or an attachment. */
    public static final String DELETE = ContextEnum.PAGE_DELETE.getRequestContext();

    /** User is deleting an existing group. */
    public static final String DELETE_GROUP = ContextEnum.GROUP_DELETE.getRequestContext();

    /** User is viewing a DIFF between the two versions of the page. */
    public static final String DIFF = ContextEnum.PAGE_DIFF.getRequestContext();

    /** The EDIT context - the user is editing the page. */
    public static final String EDIT = ContextEnum.PAGE_EDIT.getRequestContext();

    /** User is editing an existing group. */
    public static final String EDIT_GROUP = ContextEnum.GROUP_EDIT.getRequestContext();

    /** An error has been encountered and the user needs to be informed. */
    public static final String ERROR = ContextEnum.WIKI_ERROR.getRequestContext();

    /** User is searching for content. */
    public static final String FIND = ContextEnum.WIKI_FIND.getRequestContext();

    /** User is viewing page history. */
    public static final String INFO = ContextEnum.PAGE_INFO.getRequestContext();

    /** User is administering JSPWiki (Install, SecurityConfig). */
    public static final String INSTALL = ContextEnum.WIKI_INSTALL.getRequestContext();

    /** User is preparing for a login/authentication. */
    public static final String LOGIN = ContextEnum.WIKI_LOGIN.getRequestContext();

    /** User is preparing to log out. */
    public static final String LOGOUT = ContextEnum.WIKI_LOGOUT.getRequestContext();

    /** JSPWiki wants to display a message. */
    public static final String MESSAGE = ContextEnum.WIKI_MESSAGE.getRequestContext();

    /** This is not a JSPWiki context, use it to access static files. */
    public static final String NONE = ContextEnum.PAGE_NONE.getRequestContext();

    /** Same as NONE; this is just a clarification. */
    public static final String OTHER = ContextEnum.PAGE_NONE.getRequestContext();

    /** User is editing preferences */
    public static final String PREFS = ContextEnum.WIKI_PREFS.getRequestContext();

    /** User is previewing the changes he just made. */
    public static final String PREVIEW = ContextEnum.PAGE_PREVIEW.getRequestContext();

    /** User is renaming a page. */
    public static final String RENAME = ContextEnum.PAGE_RENAME.getRequestContext();

    /** RSS feed is being generated. */
    public static final String RSS = ContextEnum.PAGE_RSS.getRequestContext();

    /** User is uploading something. */
    public static final String UPLOAD = ContextEnum.PAGE_UPLOAD.getRequestContext();

    /** The VIEW context - the user just wants to view the page contents. */
    public static final String VIEW = ContextEnum.PAGE_VIEW.getRequestContext();

    /** User is viewing an existing group */
    public static final String VIEW_GROUP = ContextEnum.GROUP_VIEW.getRequestContext();

    /** User wants to view or administer workflows. */
    public static final String WORKFLOW = ContextEnum.WIKI_WORKFLOW.getRequestContext();

    private static final Logger LOG = LogManager.getLogger( WikiContext.class );

    private static final Permission DUMMY_PERMISSION = new PropertyPermission( "os.name", "read" );

    /**
     *  Create a new WikiContext for the given WikiPage. Delegates to {@link #WikiContext(Engine, HttpServletRequest, Page)}.
     *
     *  @param engine The Engine that is handling the request.
     *  @param page The WikiPage. If you want to create a WikiContext for an older version of a page, you must use this constructor.
     */
    public WikiContext( final Engine engine, final Page page ) {
        this( engine, null, findCommand( engine, null, page ) );
    }

    /**
     * <p>
     * Creates a new WikiContext for the given Engine, Command and HttpServletRequest.
     * </p>
     * <p>
     * This constructor will also look up the HttpSession associated with the request, and determine if a Session object is present.
     * If not, a new one is created.
     * </p>
     * @param engine The Engine that is handling the request
     * @param request The HttpServletRequest that should be associated with this context. This parameter may be <code>null</code>.
     * @param command the command
     * @throws IllegalArgumentException if <code>engine</code> or <code>command</code> are <code>null</code>
     */
    public WikiContext( final Engine engine, final HttpServletRequest request, final Command command ) throws IllegalArgumentException {
        if ( engine == null || command == null ) {
            throw new IllegalArgumentException( "Parameter engine and command must not be null." );
        }

        this.engine = engine;
        this.request = request;
        this.session = Wiki.session().find( engine, request );
        this.command = command;

        // If PageCommand, get the WikiPage
        if( command instanceof PageCommand pageCommand ) {
            this.page = ( WikiPage )pageCommand.getTarget();
        }

        // If page not supplied, default to front page to avoid NPEs
        if( this.page == null ) {
            this.page = ( WikiPage )engine.getManager( PageManager.class ).getPage( engine.getFrontPage() );

            // Front page does not exist?
            if( this.page == null ) {
                this.page = ( WikiPage )Wiki.contents().page( engine, engine.getFrontPage() );
            }
        }

        this.realPage = this.page;

        // Special case: retarget any empty 'view' PageCommands to the front page
        if ( PageCommand.VIEW.equals( command ) && command.getTarget() == null ) {
            this.command = command.targetedCommand( this.page );
        }

        // Debugging...
        final HttpSession httpSession = ( request == null ) ? null : request.getSession( false );
        final String sid = httpSession == null ? "(null)" : httpSession.getId();
        LOG.debug( "Creating WikiContext for session ID={}; target={}", sid, getName() );

        // Figure out what template to use
        setDefaultTemplate( request );
    }

    /**
     * Creates a new WikiContext for the given Engine, WikiPage and HttpServletRequest. This method simply looks up the appropriate
     * Command using {@link #findCommand(Engine, HttpServletRequest, Page)} and delegates to
     * {@link #WikiContext(Engine, HttpServletRequest, Command)}.
     *
     * @param engine The Engine that is handling the request
     * @param request The HttpServletRequest that should be associated with this context. This parameter may be <code>null</code>.
     * @param page The WikiPage. If you want to create a WikiContext for an older version of a page, you must supply this parameter
     */
    public WikiContext( final Engine engine, final HttpServletRequest request, final Page page ) {
        this( engine, request, findCommand( engine, request, page ) );
    }

    /**
     *  Creates a new WikiContext from a supplied HTTP request, using a default wiki context.
     *
     *  @param engine The Engine that is handling the request
     *  @param request the HTTP request
     *  @param requestContext the default context to use
     *  @see com.wikantik.ui.CommandResolver
     *  @see com.wikantik.api.core.Command
     *  @since 2.1.15.
     */
    public WikiContext( final Engine engine, final HttpServletRequest request, final String requestContext ) {
        this( engine, request, engine.getManager( CommandResolver.class ).findCommand( request, requestContext ) );
        if( !engine.isConfigured() ) {
            throw new InternalWikiException( "Engine has not been properly started.  It is likely that the configuration is faulty.  Please check all logs for the possible reason." );
        }
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.api.core.Command#getContentTemplate()
     */
    @Override
    public String getContentTemplate()
    {
        return command.getContentTemplate();
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.api.core.Command#getJSP()
     */
    @Override
    public String getJSP()
    {
        return command.getContentTemplate();
    }

    /**
     *  Sets a reference to the real page whose content is currently being rendered.
     *  <p>
     *  Sometimes you may want to render the page using some other page's context. In those cases, it is highly recommended that you set
     *  the setRealPage() to point at the real page you are rendering.  Please see InsertPageTag for an example.
     *  <p>
     *  Also, if your plugin e.g. does some variable setting, be aware that if it is embedded in the LeftMenu or some other page added
     *  with InsertPageTag, you should consider what you want to do - do you wish to really reference the "master" page or the included
     *  page.
     *
     *  @param page  The real page which is being rendered.
     *  @return The previous real page
     *  @since 2.3.14
     *  @see com.wikantik.tags.InsertPageTag
     */
    @Override
    public WikiPage setRealPage( final Page page ) {
        final WikiPage old = realPage;
        realPage = ( WikiPage )page;
        updateCommand( command.getRequestContext() );
        return old;
    }

    /**
     *  Gets a reference to the real page whose content is currently being rendered. If your plugin e.g. does some variable setting, be
     *  aware that if it is embedded in the LeftMenu or some other page added with InsertPageTag, you should consider what you want to
     *  do - do you wish to really reference the "master" page or the included page.
     *  <p>
     *  For example, in the default template, there is a page called "LeftMenu". Whenever you access a page, e.g. "Main", the master
     *  page will be Main, and that's what the getPage() will return - regardless of whether your plugin resides on the LeftMenu or on
     *  the Main page.  However, getRealPage() will return "LeftMenu".
     *
     *  @return A reference to the real page.
     *  @see com.wikantik.tags.InsertPageTag
     */
    @Override
    public WikiPage getRealPage()
    {
        return realPage;
    }

    /**
     *  Figure out to which page we are really going to.  Considers special page names from the wikantik.properties, and possible aliases.
     *  This method forwards requests to {@link com.wikantik.ui.CommandResolver#getSpecialPageReference(String)}.
     *  @return A complete URL to the new page to redirect to
     *  @since 2.2
     */
    @Override
    public String getRedirectURL() {
        final String pagename = page.getName();
        String redirURL = engine.getManager( CommandResolver.class ).getSpecialPageReference( pagename );
        if( redirURL == null ) {
            final String alias = page.getAttribute( WikiPage.ALIAS );
            if( alias != null ) {
                redirURL = getViewURL( alias );
            } else {
                redirURL = page.getAttribute( WikiPage.REDIRECT );
            }
        }

        return redirURL;
    }

    /**
     *  Returns the handling engine.
     *
     *  @return The engine owning this context.
     */
    @Override
    public WikiEngine getEngine() {
        return ( WikiEngine )engine;
    }

    /**
     *  Returns the page that is being handled.
     *
     *  @return the page which was fetched.
     */
    @Override
    public final WikiPage getPage()
    {
        return page;
    }

    /**
     *  Sets the page that is being handled.
     *
     *  @param page The wikipage
     *  @since 2.1.37.
     */
    @Override
    public void setPage( final Page page ) {
        this.page = (WikiPage)page;
        updateCommand( command.getRequestContext() );
    }

    /**
     *  Returns the request context.
     *
     *  @return The name of the request context (e.g. VIEW).
     */
    @Override
    public String getRequestContext()
    {
        return command.getRequestContext();
    }

    /**
     *  Sets the request context.  See above for the different request contexts (VIEW, EDIT, etc.)
     *
     *  @param arg The request context (one of the predefined contexts.)
     */
    @Override
    public void setRequestContext( final String arg )
    {
        updateCommand( arg );
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.api.core.Command#getTarget()
     */
    @Override
    public Object getTarget()
    {
        return command.getTarget();
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.api.core.Command#getURLPattern()
     */
    @Override
    public String getURLPattern()
    {
        return command.getURLPattern();
    }

    /**
     *  Gets a previously set variable.
     *
     *  @param key The variable name.
     *  @return The variable contents.
     */
    @Override
    @SuppressWarnings( "unchecked" )
    public < T > T getVariable( final String key ) {
        return ( T )variableMap.get( key );
    }

    /**
     *  Sets a variable.  The variable is valid while the WikiContext is valid, i.e. while page processing continues.  The variable data
     *  is discarded once the page processing is finished.
     *
     *  @param key The variable name.
     *  @param data The variable value.
     */
    @Override
    public void setVariable( final String key, final Object data ) {
        variableMap.put( key, data );
        updateCommand( command.getRequestContext() );
    }

    /**
     * This is just a simple helper method which will first check the context if there is already an override in place, and if there is not,
     * it will then check the given properties.
     *
     * @param key What key are we searching for?
     * @param defValue Default value for the boolean
     * @return {@code true} or {@code false}.
     */
    @Override
    public boolean getBooleanWikiProperty( final String key, final boolean defValue ) {
        final String bool = getVariable( key );
        if( bool != null ) {
            return TextUtil.isPositive( bool );
        }

        return TextUtil.getBooleanProperty( getEngine().getWikiProperties(), key, defValue );
    }

    /**
     *  This method will safely return any HTTP parameters that might have been defined.  You should use this method instead
     *  of peeking directly into the result of getHttpRequest(), since this method is smart enough to do all the right things,
     *  figure out UTF-8 encoded parameters, etc.
     *
     *  @since 2.0.13.
     *  @param paramName Parameter name to look for.
     *  @return HTTP parameter, or null, if no such parameter existed.
     */
    @Override
    public String getHttpParameter( final String paramName ) {
        String result = null;
        if( request != null ) {
            result = request.getParameter( paramName );
        }

        return result;
    }

    /**
     *  If the request did originate from an HTTP request, then the HTTP request can be fetched here.  However, if the request
     *  did NOT originate from an HTTP request, then this method will return null, and YOU SHOULD CHECK FOR IT!
     *
     *  @return Null, if no HTTP request was done.
     *  @since 2.0.13.
     */
    @Override
    public HttpServletRequest getHttpRequest()
    {
        return request;
    }

    /**
     *  Sets the template to be used for this request.
     *
     *  @param dir The template name
     *  @since 2.1.15.
     */
    @Override
    public void setTemplate( final String dir )
    {
        template = dir;
    }

    /**
     * Returns the target of this wiki context: a page, group name or JSP. If the associated Command is a PageCommand, this method
     * returns the page's name. Otherwise, this method delegates to the associated Command's {@link com.wikantik.api.core.Command#getName()}
     * method. Calling classes can rely on the results of this method for looking up canonically-correct page or group names. Because it
     * does not automatically assume that the wiki context is a PageCommand, calling this method is inherently safer than calling
     * {@code getPage().getName()}.
     *
     * @return the name of the target of this wiki context
     * @see com.wikantik.ui.PageCommand#getName()
     * @see com.wikantik.ui.GroupCommand#getName()
     */
    @Override
    public final String getName() {
        if ( command instanceof PageCommand ) {
            return page != null ? page.getName() : "<no page>";
        }
        return command.getName();
    }

    /**
     *  Gets the template that is to be used throughout this request.
     *
     *  @since 2.1.15.
     *  @return template name
     */
    @Override
    public String getTemplate()
    {
        return template;
    }

    /**
     *  Convenience method that gets the current user. Delegates the lookup to the WikiSession associated with this WikiContect.
     *  May return null, in case the current user has not yet been determined; or this is an internal system. If the WikiSession has not
     *  been set, <em>always</em> returns null.
     *
     *  @return The current user; or maybe null in case of internal calls.
     */
    @Override
    public Principal getCurrentUser() {
        if (session == null) {
            // This shouldn't happen, really...
            return WikiPrincipal.GUEST;
        }
        return session.getUserPrincipal();
    }

    /**
     *  A shortcut to generate a VIEW url.
     *
     *  @param page The page to which to link.
     *  @return A URL to the page.  This honours the current absolute/relative setting.
     */
    @Override
    public String getViewURL( final String page ) {
        return getURL( ContextEnum.PAGE_VIEW.getRequestContext(), page, null );
    }

    /**
     *  Creates a URL for the given request context.
     *
     *  @param context e.g. WikiContext.EDIT
     *  @param page The page to which to link
     *  @return A URL to the page, honours the absolute/relative setting in wikantik.properties
     */
    @Override
    public String getURL( final String context, final String page ) {
        return getURL( context, page, null );
    }

    /**
     *  Returns a URL from a page. It this WikiContext instance was constructed with an actual HttpServletRequest, we will attempt to
     *  construct the URL using HttpUtil, which preserves the HTTPS portion if it was used.
     *
     *  @param context The request context (e.g. WikiContext.UPLOAD)
     *  @param page The page to which to link
     *  @param params A list of parameters, separated with "&amp;"
     *
     *  @return A URL to the given context and page.
     */
    @Override
    public String getURL( final String context, final String page, final String params ) {
        // TODO: Remove with JSP UI — redirect PAGE_VIEW and PAGE_EDIT links to React SPA when rendering for React
        final String reactBase = (String) getVariable( Context.VAR_REACT_URL_BASE );
        if ( reactBase != null ) {
            if ( ContextEnum.PAGE_VIEW.getRequestContext().equals( context ) ) {
                final String engineUrl = engine.getURL( context, page, params );
                // Strip the engine's context path prefix so we can prepend the React base instead
                final String engineBase = engine.getBaseURL(); // e.g. "" for ROOT, "/wikantik" for non-ROOT
                return reactBase + engineUrl.substring( engineBase.length() );
            }
            if ( ContextEnum.PAGE_EDIT.getRequestContext().equals( context ) ) {
                // TODO: Remove with JSP UI — React edit route is /app/edit/<name>
                return reactBase + "/edit/" + engine.encodeName( page );
            }
        }
        // FIXME: is rather slow
        return engine.getURL( context, page, params );
    }

    /**
     * Returns the Command associated with this WikiContext.
     *
     * @return the command
     */
    @Override
    public Command getCommand() {
        return command;
    }

    /**
     *  Returns a shallow clone of the WikiContext.
     *
     *  @since 2.1.37.
     *  @return A shallow clone of the WikiContext
     */
    @Override
    public WikiContext clone() {
        try {
            // super.clone() must always be called to make sure that inherited objects
            // get the right type
            final WikiContext copy = (WikiContext)super.clone();

            copy.engine = engine;
            copy.command = command;

            copy.template    = template;
            copy.variableMap = variableMap;
            copy.request     = request;
            copy.session     = session;
            copy.page        = page;
            copy.realPage    = realPage;
            return copy;
        } catch( final CloneNotSupportedException e ){} // Never happens

        return null;
    }

    /**
     *  Creates a deep clone of the WikiContext.  This is useful when you want to be sure that you don't accidentally mess with page
     *  attributes, etc.
     *
     *  @since  2.8.0
     *  @return A deep clone of the WikiContext.
     */
    @Override
    @SuppressWarnings("unchecked")
    public WikiContext deepClone() {
        try {
            // super.clone() must always be called to make sure that inherited objects
            // get the right type
            final WikiContext copy = (WikiContext)super.clone();

            //  No need to deep clone these
            copy.engine  = engine;
            copy.command = command; // Static structure

            copy.template    = template;
            copy.variableMap = (HashMap<String,Object>)variableMap.clone();
            copy.request     = request;
            copy.session     = session;
            copy.page        = page.clone();
            copy.realPage    = realPage.clone();
            return copy;
        }
        catch( final CloneNotSupportedException e ){} // Never happens

        return null;
    }

    /**
     *  Returns the Session associated with the context. This method is guaranteed to always return a valid Session.
     *  If this context was constructed without an associated HttpServletRequest, it will return
     *  {@link com.wikantik.WikiSession#guestSession(Engine)}.
     *
     *  @return The Session associated with this context.
     */
    @Override
    public WikiSession getWikiSession() {
        return ( WikiSession )session;
    }

    /**
     * This method can be used to find the WikiContext programmatically from a JSP PageContext. We check the request context.
     * The wiki context, if it exists, is looked up using the key {@link #ATTR_CONTEXT}.
     *
     * @since 2.4
     * @param pageContext the JSP page context
     * @return Current WikiContext, or null, of no context exists.
     * @deprecated use {@link Context#findContext( PageContext )} instead.
     * @see Context#findContext( PageContext )
     */
    @Deprecated
    public static WikiContext findContext( final PageContext pageContext ) {
        final HttpServletRequest request = ( HttpServletRequest )pageContext.getRequest();
        return ( WikiContext )request.getAttribute( ATTR_CONTEXT );
    }

    /**
     * Returns the permission required to successfully execute this context. For example, a wiki context of VIEW for a certain page
     * means that the PagePermission "view" is required for the page. In some cases, no particular permission is required, in which case
     * a dummy permission will be returned ({@link java.util.PropertyPermission}<code> "os.name", "read"</code>). This method is guaranteed
     * to always return a valid, non-null permission.
     *
     * @return the permission
     * @since 2.4
     */
    @Override
    public Permission requiredPermission() {
        // This is a filthy rotten hack -- absolutely putrid
        if ( WikiCommand.INSTALL.equals( command ) ) {
            // See if admin users exists
            try {
                final UserManager userMgr = engine.getManager( UserManager.class );
                final UserDatabase userDb = userMgr.getUserDatabase();
                userDb.findByLoginName( Installer.ADMIN_ID );
            } catch ( final NoSuchPrincipalException e ) {
                return DUMMY_PERMISSION;
            }
            return new AllPermission( engine.getApplicationName() );
        }

        // TODO: we should really break the contract so that this
        // method returns null, but until then we will use this hack
        if( command.requiredPermission() == null ) {
            return DUMMY_PERMISSION;
        }

        return command.requiredPermission();
    }

    /**
     * Associates a target with the current Command and returns the new targeted Command. If the Command associated with this
     * WikiContext is already "targeted", it is returned instead.
     *
     * @see com.wikantik.api.core.Command#targetedCommand(java.lang.Object)
     *
     * {@inheritDoc}
     */
    @Override
    public Command targetedCommand( final Object target ) {
        if ( command.getTarget() == null ) {
            return command.targetedCommand( target );
        }
        return command;
    }

    /**
     *  Returns true, if the current user has administrative permissions (i.e. the omnipotent AllPermission).
     *
     *  @since 2.4.46
     *  @return true, if the user has all permissions.
     */
    @Override
    public boolean hasAdminPermissions() {
        return engine.getManager( AuthorizationManager.class ).checkPermission( getWikiSession(), new AllPermission( engine.getApplicationName() ) );
    }

    /**
     * Figures out which template a new WikiContext should be using.
     * @param request the HTTP request
     */
    protected final void setDefaultTemplate( final HttpServletRequest request ) {
        final String defaultTemplate = engine.getTemplateDir();

        //  Figure out which template we should be using for this page.
        String template = null;
        if ( request != null ) {
            final String skin = request.getParameter( "skin" );
            if( skin != null )
            {
                template = skin.replaceAll("\\p{Punct}", "");
            }

        }

        // If request doesn't supply the value, extract from wiki page
        if( template == null ) {
            final WikiPage page = getPage();
            if ( page != null ) {
                template = page.getAttribute( Engine.PROP_TEMPLATEDIR );
            }

        }

        // If something over-wrote the default, set the new value.
        if ( template != null ) {
            setTemplate( template );
        } else {
            setTemplate( defaultTemplate );
        }
    }

    /**
     * Looks up and returns a PageCommand based on a supplied WikiPage and HTTP request. First, the appropriate Command is obtained by
     * examining the HTTP request; the default is {@link ContextEnum#PAGE_VIEW}. If the Command is a PageCommand (and it should be, in most
     * cases), a targeted Command is created using the (non-<code>null</code>) WikiPage as target.
     *
     * @param engine the wiki engine
     * @param request the HTTP request
     * @param page the wiki page
     * @return the correct command
     */
    protected static Command findCommand( final Engine engine, final HttpServletRequest request, final Page page ) {
        final String defaultContext = ContextEnum.PAGE_VIEW.getRequestContext();
        Command command = engine.getManager( CommandResolver.class ).findCommand( request, defaultContext );
        if ( command instanceof PageCommand && page != null ) {
            command = command.targetedCommand( page );
        }
        return command;
    }

    /**
     * Protected method that updates the internally cached Command. Will always be called when the page name, request context, or variable
     * changes.
     *
     * @param requestContext the desired request context
     * @since 2.4
     */
    protected void updateCommand( final String requestContext ) {
        if ( requestContext == null ) {
            command = PageCommand.NONE;
        } else {
            final CommandResolver resolver = engine.getManager( CommandResolver.class );
            command = resolver.findCommand( request, requestContext );
        }

        if ( command instanceof PageCommand && page != null ) {
            command = command.targetedCommand( page );
        }
    }

}
