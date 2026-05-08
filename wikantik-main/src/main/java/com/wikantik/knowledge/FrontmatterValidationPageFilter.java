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
package com.wikantik.knowledge;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Save-time guard that rejects pages whose YAML frontmatter is malformed.
 * Closes the failure mode where {@link FrontmatterParser#parse(String)} silently
 * degrades to empty metadata + a {@code WARN} log when, for example, an unquoted
 * colon in a {@code title:} value confuses SnakeYAML — the page would save
 * successfully but lose all metadata, and downstream readers (search index,
 * structural index, /for-agent projection, web meta tags) would render with
 * empty fields.
 *
 * <p>Catches every save path including REST, JSP editor, and direct provider
 * writes — the MCP write tools also auto-normalize before reaching this filter
 * via {@code FrontmatterNormalizer}, so an agent's malformed YAML is fixed
 * before it gets here. This filter is the global net for everyone else.</p>
 *
 * <p>Gated by {@link #PROP_ENFORCEMENT_ENABLED} (default {@code true}). Set to
 * {@code false} only as a temporary escape hatch while running an audit pass via
 * {@code GET /admin/frontmatter-issues}.</p>
 */
public class FrontmatterValidationPageFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( FrontmatterValidationPageFilter.class );

    /** Master flag; default {@code true}. Disable only while migrating an existing dirty corpus. */
    public static final String PROP_ENFORCEMENT_ENABLED = "wikantik.frontmatter.enforcement.enabled";

    private final boolean enabled;

    public FrontmatterValidationPageFilter( final Properties props ) {
        this.enabled = Boolean.parseBoolean(
                props.getProperty( PROP_ENFORCEMENT_ENABLED, "true" ) );
        LOG.info( "FrontmatterValidationPageFilter: enforcement {}",
                  enabled ? "enabled" : "disabled" );
    }

    @Override
    public String preSave( final Context context, final String content ) throws FilterException {
        if ( !enabled || content == null || content.isEmpty() ) {
            return content;
        }
        // Only validate when there's actually a frontmatter block to validate. Pages
        // without a leading "---" delimiter pass through untouched.
        if ( !content.startsWith( "---\n" ) && !content.startsWith( "---\r\n" ) ) {
            return content;
        }
        try {
            FrontmatterParser.parseStrict( content );
        } catch ( final FrontmatterParseException e ) {
            final String pageName = context != null && context.getPage() != null
                    ? context.getPage().getName() : "<unknown>";
            final StringBuilder msg = new StringBuilder();
            msg.append( "Page '" ).append( pageName ).append( "' has malformed YAML frontmatter: " );
            msg.append( e.getMessage() );
            if ( e.line() > 0 ) {
                msg.append( " (line " ).append( e.line() );
                if ( e.column() > 0 ) {
                    msg.append( ", column " ).append( e.column() );
                }
                msg.append( ')' );
            }
            msg.append( ". Wrap values containing ':' or other YAML special characters in "
                + "double quotes, e.g. title: \"Foo: Bar\"." );
            throw new FilterException( msg.toString(), e );
        }
        return content;
    }
}
