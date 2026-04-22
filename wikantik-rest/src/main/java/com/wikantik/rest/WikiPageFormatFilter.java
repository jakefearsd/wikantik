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
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.util.BaseUrlResolver;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves page content directly for <code>/wiki/{slug}?format=md</code> and
 * <code>/wiki/{slug}?format=json</code> requests so search engines and RAG
 * pipelines (e.g. OpenWebUI's web loader) can fetch page bodies without
 * executing the SPA. Requests without a <code>format</code> parameter are
 * passed through to {@link SpaRoutingFilter} and behave exactly as before.
 *
 * <p>This filter must be declared in <code>web.xml</code> before
 * <code>SpaRoutingFilter</code>, mapped on <code>/wiki/*</code>.
 */
public class WikiPageFormatFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( WikiPageFormatFilter.class );

    private static final Gson GSON = new GsonBuilder()
            .setDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" )
            .create();

    private static final int SUMMARY_MAX_CHARS = 300;

    private volatile Engine engine;
    private ServletContext servletContext;

    @Override
    public void init( final FilterConfig filterConfig ) {
        this.servletContext = filterConfig != null ? filterConfig.getServletContext() : null;
    }

    private Engine resolveEngine() {
        Engine e = engine;
        if ( e == null && servletContext != null ) {
            try {
                e = Wiki.engine().find( servletContext, null );
                if ( e != null ) {
                    engine = e;
                }
            } catch ( final RuntimeException ex ) {
                LOG.warn( "WikiPageFormatFilter: engine lookup failed: {}", ex.getMessage() );
            }
        }
        return e;
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;

        final String format = req.getParameter( "format" );
        if ( format == null || format.isEmpty() ) {
            chain.doFilter( request, response );
            return;
        }
        if ( !"md".equalsIgnoreCase( format ) && !"json".equalsIgnoreCase( format ) ) {
            chain.doFilter( request, response );
            return;
        }

        final String contextPath = req.getContextPath() != null ? req.getContextPath() : "";
        final String rawUri = req.getRequestURI();
        final String path = rawUri != null && rawUri.startsWith( contextPath )
                ? rawUri.substring( contextPath.length() )
                : rawUri;
        if ( path == null || !path.startsWith( "/wiki/" ) ) {
            chain.doFilter( request, response );
            return;
        }

        final String pageName = SpaRoutingFilter.extractPageName( path );
        if ( pageName == null || pageName.isEmpty() ) {
            chain.doFilter( request, response );
            return;
        }

        final Engine eng = resolveEngine();
        if ( eng == null ) {
            chain.doFilter( request, response );
            return;
        }
        final PageManager pm;
        try {
            pm = eng.getManager( PageManager.class );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "WikiPageFormatFilter: PageManager lookup failed for '{}': {}",
                    pageName, ex.getMessage() );
            chain.doFilter( request, response );
            return;
        }

        if ( !pm.wikiPageExists( pageName ) ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Page not found: " + pageName );
            return;
        }
        final Page page = pm.getPage( pageName );
        if ( page == null ) {
            resp.sendError( HttpServletResponse.SC_NOT_FOUND, "Page not found: " + pageName );
            return;
        }
        final String rawText = pm.getPureText( page );
        final ParsedPage parsed = FrontmatterParser.parse( rawText == null ? "" : rawText );
        final String baseUrl = BaseUrlResolver.resolve( eng, req, null );

        if ( "md".equalsIgnoreCase( format ) ) {
            writeMarkdown( resp, page, parsed, baseUrl );
        } else {
            writeJson( resp, page, parsed );
        }
    }

    private void writeMarkdown( final HttpServletResponse resp, final Page page,
                                 final ParsedPage parsed, final String baseUrl ) throws IOException {
        final String title = extractTitle( page, parsed );
        String body = parsed.body() == null ? "" : parsed.body();
        body = stripLeadingH1( body, title );
        body = rewriteInternalLinks( body, baseUrl );
        final String md = "# " + title + "\n\n" + body;
        final byte[] bytes = md.getBytes( StandardCharsets.UTF_8 );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.setContentType( "text/markdown; charset=UTF-8" );
        resp.setCharacterEncoding( "UTF-8" );
        resp.setContentLength( bytes.length );
        resp.getOutputStream().write( bytes );
    }

    private void writeJson( final HttpServletResponse resp, final Page page,
                             final ParsedPage parsed ) throws IOException {
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "slug", page.getName() );
        out.put( "title", extractTitle( page, parsed ) );
        out.put( "content", parsed.body() == null ? "" : parsed.body() );
        out.put( "summary", extractSummary( parsed ) );
        out.put( "tags", extractTags( parsed.metadata() ) );
        out.put( "created_at", extractCreated( parsed.metadata(), page ) );
        out.put( "modified_at", page.getLastModified() );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setCharacterEncoding( "UTF-8" );
        resp.getWriter().write( GSON.toJson( out ) );
    }

    static String extractTitle( final Page page, final ParsedPage parsed ) {
        final Object t = parsed.metadata().get( "title" );
        if ( t != null && !t.toString().isBlank() ) {
            return t.toString();
        }
        return page.getName();
    }

    static String extractSummary( final ParsedPage parsed ) {
        final Object summary = parsed.metadata().get( "summary" );
        if ( summary != null && !summary.toString().isBlank() ) {
            return truncate( summary.toString(), SUMMARY_MAX_CHARS );
        }
        final Object description = parsed.metadata().get( "description" );
        if ( description != null && !description.toString().isBlank() ) {
            return truncate( description.toString(), SUMMARY_MAX_CHARS );
        }
        final String body = parsed.body() == null ? "" : parsed.body();
        final StringBuilder firstPara = new StringBuilder();
        for ( final String line : body.split( "\r?\n" ) ) {
            final String stripped = line.trim();
            if ( stripped.isEmpty() ) {
                if ( firstPara.length() > 0 ) {
                    break;
                }
                continue;
            }
            if ( stripped.startsWith( "#" ) ) {
                continue;
            }
            if ( firstPara.length() > 0 ) {
                firstPara.append( ' ' );
            }
            firstPara.append( stripped );
        }
        return truncate( firstPara.toString(), SUMMARY_MAX_CHARS );
    }

    private static String truncate( final String s, final int max ) {
        if ( s.length() <= max ) {
            return s;
        }
        return s.substring( 0, max - 1 ) + "\u2026";
    }

    static List< String > extractTags( final Map< String, Object > meta ) {
        final Object tags = meta.get( "tags" );
        if ( tags == null ) {
            return List.of();
        }
        if ( tags instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>( list.size() );
            for ( final Object o : list ) {
                if ( o != null ) {
                    out.add( o.toString().trim() );
                }
            }
            return out;
        }
        final String csv = tags.toString();
        if ( csv.isBlank() ) {
            return List.of();
        }
        final List< String > out = new ArrayList<>();
        for ( final String p : csv.split( "," ) ) {
            final String trimmed = p.trim();
            if ( !trimmed.isEmpty() ) {
                out.add( trimmed );
            }
        }
        return out;
    }

    static Date extractCreated( final Map< String, Object > meta, final Page page ) {
        final Object created = meta.get( "created" );
        if ( created instanceof Date d ) {
            return d;
        }
        if ( created != null && !created.toString().isBlank() ) {
            try {
                final LocalDate ld = LocalDate.parse( created.toString() );
                return Date.from( ld.atStartOfDay( ZoneOffset.UTC ).toInstant() );
            } catch ( final DateTimeParseException ex ) {
                LOG.warn( "WikiPageFormatFilter: unparseable 'created' frontmatter '{}': {}",
                        created, ex.getMessage() );
            }
        }
        final Object date = meta.get( "date" );
        if ( date instanceof Date d ) {
            return d;
        }
        return page.getLastModified();
    }

    static String stripLeadingH1( final String body, final String title ) {
        final String trimmed = body.stripLeading();
        if ( !trimmed.startsWith( "# " ) ) {
            return body;
        }
        final int nl = trimmed.indexOf( '\n' );
        final String firstLine = ( nl < 0 ? trimmed : trimmed.substring( 0, nl ) ).substring( 2 ).trim();
        if ( !firstLine.equals( title ) ) {
            return body;
        }
        if ( nl < 0 ) {
            return "";
        }
        return trimmed.substring( nl + 1 ).stripLeading();
    }

    static String rewriteInternalLinks( final String body, final String baseUrl ) {
        final String base = baseUrl == null ? ""
                : ( baseUrl.endsWith( "/" ) ? baseUrl.substring( 0, baseUrl.length() - 1 ) : baseUrl );
        final StringBuilder out = new StringBuilder( body.length() + 64 );
        int i = 0;
        while ( i < body.length() ) {
            final int open = body.indexOf( "](", i );
            if ( open < 0 ) {
                out.append( body, i, body.length() );
                break;
            }
            final int close = body.indexOf( ')', open + 2 );
            if ( close < 0 ) {
                out.append( body, i, body.length() );
                break;
            }
            out.append( body, i, open + 2 );
            final String target = body.substring( open + 2, close );
            final boolean isImage = isImageLink( body, open );
            if ( !isImage && isInternalTarget( target ) ) {
                out.append( base ).append( "/wiki/" ).append( target );
            } else {
                out.append( target );
            }
            out.append( ')' );
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * Returns true when the <code>](</code> at position <code>close</code>
     * belongs to an image link (<code>![alt](target)</code>). Walks backward
     * from <code>close</code> looking for the opening <code>[</code> and
     * checks whether it is preceded by a <code>!</code>.
     */
    private static boolean isImageLink( final String body, final int close ) {
        for ( int k = close - 1; k >= 0; k-- ) {
            final char c = body.charAt( k );
            if ( c == '[' ) {
                return k > 0 && body.charAt( k - 1 ) == '!';
            }
            if ( c == ']' ) {
                return false;
            }
        }
        return false;
    }

    private static boolean isInternalTarget( final String t ) {
        return t != null && !t.isEmpty()
                && !t.contains( "/" )
                && !t.contains( ":" )
                && !t.startsWith( "#" );
    }

    @Override
    public void destroy() {
    }
}
