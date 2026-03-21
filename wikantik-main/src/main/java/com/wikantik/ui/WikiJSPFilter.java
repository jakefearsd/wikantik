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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import com.wikantik.WatchDog;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.pages.PageManager;
import com.wikantik.url.URLConstructor;
import com.wikantik.util.HttpUtil;
import com.wikantik.util.TextUtil;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;


/**
 * This filter goes through the generated page response prior and places requested resources at the appropriate inclusion markers.
 * This is done to let dynamic content (e.g. plugins, editors) include custom resources, even after the HTML head section is
 * in fact built. This filter is typically the last filter to execute, and it <em>must</em> run after servlet or JSP code that performs
 * redirections or sends error codes (such as access control methods).
 * <p>
 * Inclusion markers are placed by the IncludeResourcesTag; the default content templates (see .../templates/default/commonheader.jsp)
 * are configured to do this. As an example, a JavaScript resource marker is added like this:
 * <pre>
 * &lt;wiki:IncludeResources type="script"/&gt;
 * </pre>
 * Any code that requires special resources must register a resource request with the TemplateManager. For example:
 * <pre>
 * &lt;wiki:RequestResource type="script" path="scripts/custom.js" /&gt;
 * </pre>
 * or programmatically,
 * <pre>
 * TemplateManager.addResourceRequest( context, TemplateManager.RESOURCE_SCRIPT, "scripts/customresource.js" );
 * </pre>
 *
 * @see TemplateManager
 * @see com.wikantik.tags.RequestResourceTag
 */
public class WikiJSPFilter extends WikiServletFilter {

    private static final Logger LOG = LogManager.getLogger( WikiJSPFilter.class );
    private String wiki_encoding;
    private boolean useEncoding;

    /** {@inheritDoc} */
    @Override
    public void init( final FilterConfig config ) throws ServletException {
        super.init( config );
        wiki_encoding = engine.getWikiProperties().getProperty( Engine.PROP_ENCODING );

        useEncoding = !Boolean.parseBoolean( engine.getWikiProperties().getProperty( Engine.PROP_NO_FILTER_ENCODING, "false" ).trim() );
    }

    @Override
    public void doFilter( final ServletRequest  request, final ServletResponse response, final FilterChain chain ) throws ServletException, IOException {
        final WatchDog w = WatchDog.getCurrentWatchDog( engine );
        try {
            ThreadContext.push( engine.getApplicationName() + ":" + ( ( HttpServletRequest )request ).getRequestURI() );
            w.enterState("Filtering for URL "+((HttpServletRequest)request).getRequestURI(), 90 );

            final HttpServletRequest httpRequest = ( HttpServletRequest ) request;
            final HttpServletResponse httpResponse = ( HttpServletResponse ) response;
            request.setCharacterEncoding( engine.getContentEncoding().displayName() );

            // fire PAGE_REQUESTED event
            final String pagename = URLConstructor.parsePageFromURL( httpRequest, engine.getContentEncoding() );
            final String effectivePage = pagename != null ? pagename : engine.getFrontPage();

            // HTTP Conditional GET — return 304 if page hasn't changed
            if( "GET".equals( httpRequest.getMethod() ) ) {
                final Page page = engine.getManager( PageManager.class ).getPage( effectivePage );
                if( page != null && page.getLastModified() != null ) {
                    if( HttpUtil.checkFor304( httpRequest, effectivePage, page.getLastModified() ) ) {
                        httpResponse.setStatus( HttpServletResponse.SC_NOT_MODIFIED );
                        return;
                    }
                }
            }

            fireEvent( WikiPageEvent.PAGE_REQUESTED, effectivePage );
            final HttpServletResponseWrapper responseWrapper = new WikantikServletResponseWrapper( httpResponse, wiki_encoding, useEncoding );
            super.doFilter( request, responseWrapper, chain );

            // The response is now complete. Let's replace the markers now.

            // WikiContext is only available after doFilter! (That is after interpreting the jsp)

            try {
                w.enterState( "Delivering response", 30 );
                final Context wikiContext = getWikiContext( request );
                final String r = filter( wikiContext, responseWrapper );

                // Set HTTP caching headers for page views
                if( "GET".equals( httpRequest.getMethod() ) ) {
                    final Page page = engine.getManager( PageManager.class ).getPage( effectivePage );
                    if( page != null && page.getLastModified() != null ) {
                        httpResponse.setHeader( "ETag", HttpUtil.createETag( effectivePage, page.getLastModified() ) );
                        httpResponse.setDateHeader( "Last-Modified", page.getLastModified().getTime() );
                        httpResponse.setHeader( "Cache-Control", "private, no-cache" );
                    }
                }

                if( useEncoding ) {
                    final OutputStreamWriter out = new OutputStreamWriter( response.getOutputStream(), response.getCharacterEncoding() );
                    out.write( r );
                    out.flush();
                    out.close();
                } else {
                    response.getWriter().write(r);
                }

                // Clean up the UI messages and loggers
                if( wikiContext != null ) {
                    wikiContext.getWikiSession().clearMessages();
                }

                // fire PAGE_DELIVERED event
                fireEvent( WikiPageEvent.PAGE_DELIVERED, effectivePage );

            } finally {
                w.exitState();
            }
        } finally {
            w.exitState();
            ThreadContext.pop();
            ThreadContext.remove( engine.getApplicationName() + ":" + ( ( HttpServletRequest )request ).getRequestURI() );
        }
    }

    /**
     * Goes through all types and writes the appropriate response.
     *
     * @param wikiContext The usual processing context
     * @param response The source string
     * @return The modified string with all the insertions in place.
     */
    private String filter( final Context wikiContext, final HttpServletResponse response ) {
        String string = response.toString();

        if( wikiContext != null ) {
            final String[] resourceTypes = TemplateManager.getResourceTypes( wikiContext );
            for( final String resourceType : resourceTypes ) {
                string = insertResources( wikiContext, string, resourceType );
            }

            //  Add HTTP header Resource Requests
            final String[] headers = TemplateManager.getResourceRequests( wikiContext, TemplateManager.RESOURCE_HTTPHEADER );

            for( final String header : headers ) {
                String key = header;
                String value = "";
                final int split = header.indexOf( ':' );
                if( split > 0 && split < header.length() - 1 ) {
                    key = header.substring( 0, split );
                    value = header.substring( split + 1 );
                }

                response.addHeader( key.trim(), value.trim() );
            }
        }

        return string;
    }

    /**
     *  Inserts whatever resources were requested by any plugins or other components for this particular type.
     *
     *  @param wikiContext The usual processing context
     *  @param string The source string
     *  @param type Type identifier for insertion
     *  @return The filtered string.
     */
    private String insertResources( final Context wikiContext, final String string, final String type ) {
        if( wikiContext == null ) {
            return string;
        }

        final String marker = TemplateManager.getMarker( wikiContext, type );
        final int idx = string.indexOf( marker );
        if( idx == -1 ) {
            return string;
        }

        LOG.debug("...Inserting...");

        final String[] resources = TemplateManager.getResourceRequests( wikiContext, type );
        final StringBuilder concat = new StringBuilder( resources.length * 40 );

        for( final String resource : resources ) {
            LOG.debug( "...:::" + resource );
            concat.append( resource );
        }

        return TextUtil.replaceString( string, idx, idx + marker.length(), concat.toString() );
    }

    /**
     *  Simple response wrapper that just allows us to gobble through the entire
     *  response before it's output.
     */
    private static class WikantikServletResponseWrapper extends HttpServletResponseWrapper {

        final ByteArrayOutputStream output;
        private final ByteArrayServletOutputStream servletOut;
        private final PrintWriter writer;
        private final HttpServletResponse response;
        private final boolean useEncoding;

        /** How large the initial buffer should be.  This should be tuned to achieve a balance in speed and memory consumption. */
        private static final int INIT_BUFFER_SIZE = 0x8000;

        public WikantikServletResponseWrapper( final HttpServletResponse r, final String wikiEncoding, final boolean useEncoding ) throws UnsupportedEncodingException {
            super( r );
            output = new ByteArrayOutputStream( INIT_BUFFER_SIZE );
            servletOut = new ByteArrayServletOutputStream( output );
            writer = new PrintWriter( new OutputStreamWriter( servletOut, wikiEncoding ), true );
            this.useEncoding = useEncoding;

            response = r;
        }

        /** Returns a writer for output; this wraps the internal buffer into a PrintWriter. */
        @Override
        public PrintWriter getWriter() {
            return writer;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return servletOut;
        }

        @Override
        public void flushBuffer() throws IOException {
            writer.flush();
            super.flushBuffer();
        }

        class ByteArrayServletOutputStream extends ServletOutputStream {

            final ByteArrayOutputStream buffer;

            public ByteArrayServletOutputStream( final ByteArrayOutputStream byteArrayOutputStream ) {
                super();
                buffer = byteArrayOutputStream;
            }

            //
            /**{@inheritDoc} */
            @Override
            public void write( final int aInt ) {
                buffer.write( aInt );
            }

            /**{@inheritDoc} */
            @Override
			public boolean isReady() {
				return false;
			}

            /**{@inheritDoc} */
            @Override
			public void setWriteListener( final WriteListener writeListener ) {
			}
			
        }

        /** Returns whatever was written so far into the Writer. */
        @Override
        public String toString() {
            try {
				flushBuffer();
			} catch( final IOException e ) {
                LOG.error( e );
                return StringUtils.EMPTY;
			}

            try {
				if( useEncoding ) {
					return output.toString( response.getCharacterEncoding() );
				}

				return output.toString();
			} catch( final UnsupportedEncodingException e ) {
                LOG.error( e );
                return StringUtils.EMPTY;
             }
        }

    }

    // events processing .......................................................

    /**
     *  Fires a WikiPageEvent of the provided type and page name
     *  to all registered listeners of the current Engine.
     *
     * @see com.wikantik.event.WikiPageEvent
     * @param type       the event type to be fired
     * @param pagename   the wiki page name as a String
     */
    protected final void fireEvent( final int type, final String pagename ) {
        if( WikiEventManager.isListening( engine ) ) {
            WikiEventManager.fireEvent( engine, new WikiPageEvent( engine, type, pagename ) );
        }
    }

}
