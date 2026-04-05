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
package com.wikantik.ui;

import com.wikantik.api.core.Command;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.auth.GroupPrincipal;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.permissions.WikiPermission;

import java.security.Permission;
import java.util.function.Function;

/**
 * Consolidated, immutable Command implementation that replaces AbstractCommand, PageCommand, WikiCommand, GroupCommand,
 * RedirectCommand, and AllCommands. All fields are {@code final}; instances are thread-safe.
 *
 * @since 2.12
 */
public final class GenericCommand implements Command {

    /** Discriminator for the kind of operation a command represents. */
    public enum CommandKind { PAGE, WIKI, GROUP, REDIRECT }

    // ---- static command constants: page commands ----

    public static final Command PAGE_ATTACH   = pageCmd( ContextEnum.PAGE_ATTACH, PagePermission.UPLOAD_ACTION );
    public static final Command PAGE_COMMENT  = pageCmd( ContextEnum.PAGE_COMMENT, PagePermission.COMMENT_ACTION );
    public static final Command PAGE_CONFLICT = pageCmd( ContextEnum.PAGE_CONFLICT, PagePermission.VIEW_ACTION );
    public static final Command PAGE_DELETE   = pageCmd( ContextEnum.PAGE_DELETE, PagePermission.DELETE_ACTION );
    public static final Command PAGE_DIFF     = pageCmd( ContextEnum.PAGE_DIFF, PagePermission.VIEW_ACTION );
    public static final Command PAGE_EDIT     = pageCmd( ContextEnum.PAGE_EDIT, PagePermission.EDIT_ACTION );
    public static final Command PAGE_INFO     = pageCmd( ContextEnum.PAGE_INFO, PagePermission.VIEW_ACTION );
    public static final Command PAGE_PREVIEW  = pageCmd( ContextEnum.PAGE_PREVIEW, PagePermission.VIEW_ACTION );
    public static final Command PAGE_RENAME   = pageCmd( ContextEnum.PAGE_RENAME, PagePermission.RENAME_ACTION );
    public static final Command PAGE_RSS      = pageCmd( ContextEnum.PAGE_RSS, PagePermission.VIEW_ACTION );
    public static final Command PAGE_UPLOAD   = pageCmd( ContextEnum.PAGE_UPLOAD, PagePermission.UPLOAD_ACTION );
    public static final Command PAGE_VIEW     = pageCmd( ContextEnum.PAGE_VIEW, PagePermission.VIEW_ACTION );
    public static final Command PAGE_NONE     = pageCmd( ContextEnum.PAGE_NONE, null );
    public static final Command PAGE_OTHER    = PAGE_NONE;

    // ---- static command constants: wiki commands ----

    public static final Command WIKI_ADMIN        = wikiAdminCmd( ContextEnum.WIKI_ADMIN );
    public static final Command WIKI_CREATE_GROUP = wikiCmd( ContextEnum.WIKI_CREATE_GROUP, WikiPermission.CREATE_GROUPS_ACTION );
    public static final Command WIKI_ERROR        = wikiCmd( ContextEnum.WIKI_ERROR, null );
    public static final Command WIKI_FIND         = wikiCmd( ContextEnum.WIKI_FIND, null );
    public static final Command WIKI_INSTALL      = wikiCmd( ContextEnum.WIKI_INSTALL, null );
    public static final Command WIKI_LOGIN        = wikiCmd( ContextEnum.WIKI_LOGIN, WikiPermission.LOGIN_ACTION );
    public static final Command WIKI_LOGOUT       = wikiCmd( ContextEnum.WIKI_LOGOUT, WikiPermission.LOGIN_ACTION );
    public static final Command WIKI_MESSAGE      = wikiCmd( ContextEnum.WIKI_MESSAGE, null );
    public static final Command WIKI_PREFS        = wikiCmd( ContextEnum.WIKI_PREFS, WikiPermission.EDIT_PROFILE_ACTION );
    public static final Command WIKI_WORKFLOW     = wikiCmd( ContextEnum.WIKI_WORKFLOW, null );

    // ---- static command constants: group commands ----

    public static final Command GROUP_DELETE = groupCmd( ContextEnum.GROUP_DELETE, GroupPermission.DELETE_ACTION );
    public static final Command GROUP_EDIT   = groupCmd( ContextEnum.GROUP_EDIT, GroupPermission.EDIT_ACTION );
    public static final Command GROUP_VIEW   = groupCmd( ContextEnum.GROUP_VIEW, GroupPermission.VIEW_ACTION );

    // ---- static command constants: redirect command ----

    public static final Command REDIRECT = redirectCmd( ContextEnum.REDIRECT );

    // ---- instance fields (all final) ----

    private final String requestContext;
    private final String urlPattern;
    private final String contentTemplate;
    private final Object target;
    private final String routePath;
    private final String routeFriendlyName;
    private final Permission permission;
    private final Function<Object, Command> targetFactory;
    private final Function<Object, String> nameExtractor;
    private final CommandKind kind;

    // ---- constructor ----

    private GenericCommand( final String requestContext,
                            final String urlPattern,
                            final String contentTemplate,
                            final Object target,
                            final Permission permission,
                            final Function<Object, Command> targetFactory,
                            final Function<Object, String> nameExtractor,
                            final CommandKind kind ) {
        if ( requestContext == null || urlPattern == null ) {
            throw new IllegalArgumentException( "Request context, URL pattern and type must not be null." );
        }
        this.requestContext = requestContext;
        this.contentTemplate = contentTemplate;
        this.target = target;
        this.permission = permission;
        this.targetFactory = targetFactory;
        this.nameExtractor = nameExtractor;
        this.kind = kind;

        if ( urlPattern.toUpperCase().startsWith( "HTTP://" ) || urlPattern.toUpperCase().startsWith( "HTTPS://" ) ) {
            routePath = urlPattern;
            routeFriendlyName = "Special Page";
        } else {
            String localRoutePath = urlPattern;
            final int qPosition = urlPattern.indexOf( '?' );
            if ( qPosition != -1 ) {
                localRoutePath = localRoutePath.substring( 0, qPosition );
            }
            localRoutePath = removeSubstitutions( localRoutePath );
            this.routePath = localRoutePath;
            routeFriendlyName = localRoutePath;
        }
        this.urlPattern = urlPattern;
    }

    // ---- removeSubstitutions (same algorithm as AbstractCommand) ----

    private static String removeSubstitutions( final String path ) {
        final StringBuilder newPath = new StringBuilder( path.length() );
        for ( int i = 0; i < path.length(); i++ ) {
            final char c = path.charAt( i );
            if ( c == '%' && i < path.length() - 1 && Character.isLetterOrDigit( path.charAt( i + 1 ) ) ) {
                i++;
                continue;
            }
            newPath.append( c );
        }
        return newPath.toString();
    }

    // ---- Command interface methods ----

    /** {@inheritDoc} */
    @Override
    public String getContentTemplate() {
        return contentTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public String getRoutePath() {
        return routePath;
    }

    /** {@inheritDoc} */
    @Override
    public String getRequestContext() {
        return requestContext;
    }

    /** {@inheritDoc} */
    @Override
    public Object getTarget() {
        return target;
    }

    /** {@inheritDoc} */
    @Override
    public String getURLPattern() {
        return urlPattern;
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        if ( nameExtractor == null ) {
            return routeFriendlyName;
        }
        if ( target == null ) {
            return routeFriendlyName;
        }
        return nameExtractor.apply( target );
    }

    /** {@inheritDoc} */
    @Override
    public Permission requiredPermission() {
        return permission;
    }

    /** {@inheritDoc} */
    @Override
    public Command targetedCommand( final Object target ) {
        return targetFactory.apply( target );
    }

    // ---- helper / convenience methods ----

    /**
     * Returns {@code true} if this is a page command.
     *
     * @return whether this command is of kind PAGE
     */
    public boolean isPageCommand() {
        return kind == CommandKind.PAGE;
    }

    /**
     * Returns {@code true} if this is a group command.
     *
     * @return whether this command is of kind GROUP
     */
    public boolean isGroupCommand() {
        return kind == CommandKind.GROUP;
    }

    /**
     * Returns {@code true} if this is a redirect command.
     *
     * @return whether this command is of kind REDIRECT
     */
    public boolean isRedirectCommand() {
        return kind == CommandKind.REDIRECT;
    }

    /**
     * Returns the {@link CommandKind} for this command.
     *
     * @return the command kind
     */
    public CommandKind getKind() {
        return kind;
    }

    /**
     * Returns the "friendly name" derived from the route path.
     *
     * @return the friendly name
     */
    String getRouteFriendlyName() {
        return routeFriendlyName;
    }

    /**
     * Returns a String representation of the Command. Matches the format produced by {@link AbstractCommand#toString()}.
     *
     * @return string form
     */
    @Override
    public String toString() {
        return "Command" +
               "[context=" + requestContext + "," +
               "urlPattern=" + urlPattern + "," +
               "routePath=" + routePath +
               ( target == null ? "" : ",target=" + target + target ) +
               "]";
    }

    // ---- static factory: all commands ----

    /**
     * Returns a defensively-created array of all static Commands, in the same order as {@link AllCommands#get()}.
     *
     * @return the array of commands
     */
    public static Command[] allCommands() {
        return new Command[] {
            GROUP_DELETE, GROUP_EDIT, GROUP_VIEW,
            PAGE_ATTACH, PAGE_COMMENT, PAGE_CONFLICT, PAGE_DELETE, PAGE_DIFF,
            PAGE_EDIT, PAGE_INFO, PAGE_NONE, PAGE_OTHER, PAGE_PREVIEW,
            PAGE_RENAME, PAGE_RSS, PAGE_UPLOAD, PAGE_VIEW,
            REDIRECT,
            WIKI_CREATE_GROUP, WIKI_ERROR, WIKI_FIND, WIKI_INSTALL,
            WIKI_LOGIN, WIKI_LOGOUT, WIKI_MESSAGE, WIKI_PREFS, WIKI_WORKFLOW,
            WIKI_ADMIN
        };
    }

    // ---- private factory helpers ----

    /**
     * Creates a static page command. When targeted, the factory validates that the target is a {@link Page} and produces a
     * permission via {@link PermissionFactory#getPagePermission(Page, String)}.
     */
    private static GenericCommand pageCmd( final ContextEnum ctx, final String action ) {
        final String reqCtx = ctx.getRequestContext();
        final String urlPat = ctx.getUrlPattern();
        final String tmpl = ctx.getContentTemplate();
        final Function<Object, String> nameExtractor = t -> ( (Page) t ).getName();

        // The targetFactory captures action, reqCtx, urlPat, tmpl, nameExtractor by closure.
        // It will be set below via a helper to avoid forward-reference issues.
        final Function<Object, Command>[] holder = new Function[1];

        holder[0] = t -> {
            if ( !( t instanceof Page ) ) {
                throw new IllegalArgumentException( "Target must non-null and of type Page." );
            }
            final Page page = (Page) t;
            final Permission perm = ( action == null ) ? null : PermissionFactory.getPagePermission( page, action );
            return new GenericCommand( reqCtx, urlPat, tmpl, page, perm, holder[0], nameExtractor, CommandKind.PAGE );
        };

        return new GenericCommand( reqCtx, urlPat, tmpl, null, null, holder[0], nameExtractor, CommandKind.PAGE );
    }

    /**
     * Creates a static wiki command (non-admin). When targeted, the factory validates that the target is a {@link String}
     * and produces a {@link WikiPermission} if the action is non-null.
     */
    private static GenericCommand wikiCmd( final ContextEnum ctx, final String action ) {
        final String reqCtx = ctx.getRequestContext();
        final String urlPat = ctx.getUrlPattern();
        final String tmpl = ctx.getContentTemplate();

        final Function<Object, Command>[] holder = new Function[1];

        holder[0] = t -> {
            if ( !( t instanceof String ) ) {
                throw new IllegalArgumentException( "Target must non-null and of type String." );
            }
            final String wiki = (String) t;
            final Permission perm = ( action == null ) ? null : new WikiPermission( wiki, action );
            return new GenericCommand( reqCtx, urlPat, tmpl, wiki, perm, holder[0], null, CommandKind.WIKI );
        };

        return new GenericCommand( reqCtx, urlPat, tmpl, null, null, holder[0], null, CommandKind.WIKI );
    }

    /**
     * Creates a static admin wiki command. Uses {@link AllPermission} rather than {@link WikiPermission}.
     */
    private static GenericCommand wikiAdminCmd( final ContextEnum ctx ) {
        final String reqCtx = ctx.getRequestContext();
        final String urlPat = ctx.getUrlPattern();
        final String tmpl = ctx.getContentTemplate();

        final Function<Object, Command>[] holder = new Function[1];

        holder[0] = t -> {
            if ( !( t instanceof String ) ) {
                throw new IllegalArgumentException( "Target must non-null and of type String." );
            }
            final String wiki = (String) t;
            return new GenericCommand( reqCtx, urlPat, tmpl, wiki, new AllPermission( wiki ), holder[0], null, CommandKind.WIKI );
        };

        return new GenericCommand( reqCtx, urlPat, tmpl, null, new AllPermission( null ), holder[0], null, CommandKind.WIKI );
    }

    /**
     * Creates a static group command. When targeted, the factory validates that the target is a {@link GroupPrincipal}
     * and produces a {@link GroupPermission}.
     */
    private static GenericCommand groupCmd( final ContextEnum ctx, final String action ) {
        final String reqCtx = ctx.getRequestContext();
        final String urlPat = ctx.getUrlPattern();
        final String tmpl = ctx.getContentTemplate();
        final Function<Object, String> nameExtractor = t -> ( (GroupPrincipal) t ).getName();

        final Function<Object, Command>[] holder = new Function[1];

        holder[0] = t -> {
            if ( !( t instanceof GroupPrincipal ) ) {
                throw new IllegalArgumentException( "Target must non-null and of type GroupPrincipal." );
            }
            final GroupPrincipal group = (GroupPrincipal) t;
            final Permission perm = new GroupPermission( group.getName(), action );
            return new GenericCommand( reqCtx, urlPat, tmpl, group, perm, holder[0], nameExtractor, CommandKind.GROUP );
        };

        return new GenericCommand( reqCtx, urlPat, tmpl, null, null, holder[0], nameExtractor, CommandKind.GROUP );
    }

    /**
     * Creates a static redirect command. When targeted, the target String replaces the URL pattern.
     */
    private static GenericCommand redirectCmd( final ContextEnum ctx ) {
        final String reqCtx = ctx.getRequestContext();
        final String tmpl = ctx.getContentTemplate();
        final Function<Object, String> nameExtractor = Object::toString;

        final Function<Object, Command>[] holder = new Function[1];

        holder[0] = t -> {
            if ( !( t instanceof String ) ) {
                throw new IllegalArgumentException( "Target must non-null and of type String." );
            }
            final String url = (String) t;
            return new GenericCommand( reqCtx, url, tmpl, url, null, holder[0], nameExtractor, CommandKind.REDIRECT );
        };

        return new GenericCommand( reqCtx, ctx.getUrlPattern(), tmpl, null, null, holder[0], nameExtractor, CommandKind.REDIRECT );
    }

}
