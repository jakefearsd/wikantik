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
import com.wikantik.util.TextUtil;


/**
 * Abstract, immutable Command implementation class. All of the fields in this class are <code>final</code>. This class is thread-safe.
 *
 * @since 2.4.22
 */
public abstract class AbstractCommand implements Command {

    private static final String HTTPS = "HTTPS://";
    private static final String HTTP = "HTTP://";

    private final String jsp;
    private final String jspFriendlyName;
    private final String urlPattern;
    private final String requestContext;
    private final String contentTemplate;
    private final Object target;

    /**
     * Constructs a new Command with a specified wiki context, URL pattern, content template and target. The URL pattern is used to derive
     * the JSP; if it is a "local" JSP (that is, it does not contain the <code>http://</code> or <code>https://</code> prefixes),
     * then the JSP will be a cleansed version of the URL pattern; symbols (such as <code>%u</code>) will be removed. If the supplied
     * URL pattern points to a non-local destination, the JSP will be set to the value supplied, unmodified.
     *
     * @param requestContext the request context
     * @param urlPattern the URL pattern
     * @param contentTemplate the content template; may be <code>null</code>
     * @param target the target of the command, such as a WikiPage; may be <code>null</code>
     * @throws IllegalArgumentException if the request content or URL pattern is <code>null</code>
     */
    protected AbstractCommand( final String requestContext, final String urlPattern, final String contentTemplate, final Object target ) {
        if( requestContext == null || urlPattern == null ) {
            throw new IllegalArgumentException( "Request context, URL pattern and type must not be null." );
        }

        this.requestContext = requestContext;
        if ( urlPattern.toUpperCase().startsWith( HTTP ) || urlPattern.toUpperCase().startsWith( HTTPS ) ) {
            // For an HTTP/HTTPS url, pass it through without modification
            jsp = urlPattern;
            jspFriendlyName = "Special Page";
        } else {
            // For local JSPs, take everything to the left of ?, then delete all variable substitutions
            String localJsp = urlPattern;
            final int qPosition = urlPattern.indexOf( '?' );
            if ( qPosition != -1 ) {
                localJsp = localJsp.substring( 0, qPosition );
            }
            localJsp = removeSubstitutions(localJsp);
            this.jsp = localJsp;

            // Calculate the "friendly name" for the JSP
            if ( localJsp.toUpperCase().endsWith( ".JSP" ) ) {
                jspFriendlyName = TextUtil.beautifyString( localJsp.substring( 0, localJsp.length() - 4 ) );
            } else {
                jspFriendlyName = localJsp;
            }
        }
        this.urlPattern = urlPattern;
        this.contentTemplate = contentTemplate;
        this.target = target;
    }

    //
    //  This is just *so* much faster than doing String.replaceAll().  It would, in fact, be worth to cache this value.
    //
    private String removeSubstitutions( final String jsp ) {
        //return jsp.replaceAll( "\u0025[a-z|A-Z]", "" );
        final StringBuilder newjsp = new StringBuilder( jsp.length() );
        for( int i = 0; i < jsp.length(); i++ ) {
            final char c = jsp.charAt(i);
            if( c == '%' && i < jsp.length() - 1 && Character.isLetterOrDigit( jsp.charAt( i + 1 ) ) ) {
                i++;
                continue;
            }
            newjsp.append( c );
        }
        return newjsp.toString();
    }

    /**
     * @see com.wikantik.api.core.Command#targetedCommand(Object)
     */
    @Override
    public abstract Command targetedCommand(final Object target );

    /**
     * @see com.wikantik.api.core.Command#getContentTemplate()
     */
    @Override
    public final String getContentTemplate() {
        return contentTemplate;
    }

    /**
     * @see com.wikantik.api.core.Command#getJSP()
     */
    @Override
    public final String getJSP() {
        return jsp;
    }

    /**
     * @see com.wikantik.api.core.Command#getName()
     */
    @Override
    public abstract String getName();

    /**
     * @see com.wikantik.api.core.Command#getRequestContext()
     */
    @Override
    public final String getRequestContext() {
        return requestContext;
    }

    /**
     * @see com.wikantik.api.core.Command#getTarget()
     */
    @Override
    public final Object getTarget() {
        return target;
    }

    /**
     * @see com.wikantik.api.core.Command#getURLPattern()
     */
    @Override
    public final String getURLPattern() {
        return urlPattern;
    }

    /**
     * Returns the "friendly name" for this command's JSP, namely a beatified version of the JSP's name without the .jsp suffix.
     *
     * @return the friendly name
     */
    protected final String getJSPFriendlyName() {
        return jspFriendlyName;
    }

    /**
     * Returns a String representation of the Command.
     *
     * @see java.lang.Object#toString()
     */
    public final String toString() {
        return "Command" +
               "[context=" + requestContext + "," +
               "urlPattern=" + urlPattern + "," +
               "jsp=" +  jsp +
               ( target == null ? "" : ",target=" + target + target ) +
               "]";
    }

}
