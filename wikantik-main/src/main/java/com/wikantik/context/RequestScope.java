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
package com.wikantik.context;

import com.wikantik.WikiSession;
import com.wikantik.ui.CommandResolver;
import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;
import java.util.HashMap;

/**
 * Holds the HTTP-request-scoped state of a {@link com.wikantik.WikiContext}: the servlet request,
 * the wiki session, the command resolver, and the per-request variable map.
 *
 * <p>Instances are created by {@code WikiContext} constructors and are not intended to be
 * constructed directly by callers outside the {@code com.wikantik} package.</p>
 *
 * @since 2.12
 */
public final class RequestScope {

    private final HttpServletRequest request;
    private final WikiSession session;
    private final CommandResolver commandResolver;
    private final HashMap<String, Object> variableMap;

    /**
     * Constructs a new RequestScope.
     *
     * @param request         the HTTP servlet request; may be {@code null} for non-HTTP callers
     * @param session         the wiki session; must not be {@code null}
     * @param commandResolver the command resolver; must not be {@code null}
     * @param variableMap     the initial variable map; must not be {@code null}
     */
    public RequestScope( final HttpServletRequest request,
                         final WikiSession session,
                         final CommandResolver commandResolver,
                         final HashMap<String, Object> variableMap ) {
        this.request         = request;
        this.session         = session;
        this.commandResolver = commandResolver;
        this.variableMap     = variableMap;
    }

    /**
     * Returns the HTTP servlet request, or {@code null} if this context was not created from
     * an HTTP request.
     *
     * @return the HTTP servlet request, or {@code null}
     */
    public HttpServletRequest getHttpRequest() {
        return request;
    }

    /**
     * Returns the wiki session associated with this request.
     *
     * @return the wiki session; never {@code null}
     */
    public WikiSession getSession() {
        return session;
    }

    /**
     * Returns the command resolver used to map request contexts to {@link com.wikantik.api.core.Command}s.
     *
     * @return the command resolver; never {@code null}
     */
    public CommandResolver getCommandResolver() {
        return commandResolver;
    }

    /**
     * Returns the mutable per-request variable map.  Changes to the returned map are
     * reflected in this scope.
     *
     * @return the variable map; never {@code null}
     */
    public HashMap<String, Object> getVariableMap() {
        return variableMap;
    }

    /**
     * Safely returns an HTTP request parameter.  Returns {@code null} when no request is
     * associated with this scope.
     *
     * @param paramName the parameter name
     * @return the parameter value, or {@code null}
     */
    public String getHttpParameter( final String paramName ) {
        if ( request == null ) {
            return null;
        }
        return request.getParameter( paramName );
    }

    /**
     * Returns the value of a previously stored variable, or {@code null} if not set.
     *
     * @param <T> the expected type
     * @param key the variable key
     * @return the variable value, or {@code null}
     */
    @SuppressWarnings( "unchecked" )
    public <T> T getVariable( final String key ) {
        return (T) variableMap.get( key );
    }

    /**
     * Stores a variable for the duration of the current request.
     *
     * @param key   the variable key
     * @param value the variable value
     */
    public void setVariable( final String key, final Object value ) {
        variableMap.put( key, value );
    }

    /**
     * Convenience method that returns the current user principal from the wiki session.
     * Falls back to {@link com.wikantik.auth.WikiPrincipal#GUEST} when the session is
     * {@code null}.
     *
     * @return the current user principal; never {@code null}
     */
    public Principal getCurrentUser() {
        if ( session == null ) {
            return com.wikantik.auth.WikiPrincipal.GUEST;
        }
        return session.getUserPrincipal();
    }

}
