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
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * A preSave {@link PageFilter} that generates a default frontmatter block for pages
 * that are saved without any frontmatter. The generated block contains:
 * {@code title}, {@code type}, {@code tags}, {@code summary}, and
 * {@code auto-generated: true}.
 *
 * <p>Pages that already have frontmatter, or that match the system-page predicate,
 * are returned unchanged.</p>
 *
 * <p>The number of tags to extract is controlled by the
 * {@code wikantik.frontmatter.defaultTags} property (default: 3).</p>
 */
public class FrontmatterDefaultsFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( FrontmatterDefaultsFilter.class );

    public static final String PROP_AUTO_DEFAULTS = "wikantik.frontmatter.autoDefaults";
    private static final String PROP_DEFAULT_TAGS = "wikantik.frontmatter.defaultTags";
    private static final int DEFAULT_TAG_COUNT = 3;

    private final Predicate< String > isSystemPage;
    private final int tagCount;
    private final boolean enabled;

    /**
     * Constructs a new FrontmatterDefaultsFilter.
     *
     * @param isSystemPage predicate that returns {@code true} for system page names that
     *                     should be left untouched
     * @param props        filter properties; may contain {@code wikantik.frontmatter.defaultTags}
     */
    public FrontmatterDefaultsFilter( final Predicate< String > isSystemPage, final Properties props ) {
        this.isSystemPage = isSystemPage;
        this.enabled = Boolean.parseBoolean( props.getProperty( PROP_AUTO_DEFAULTS, "false" ) );
        final String tagCountProp = props.getProperty( PROP_DEFAULT_TAGS );
        int count = DEFAULT_TAG_COUNT;
        if ( tagCountProp != null ) {
            try {
                count = Integer.parseInt( tagCountProp.trim() );
            } catch ( final NumberFormatException e ) {
                LOG.warn( "Invalid value for {}: '{}', using default {}", PROP_DEFAULT_TAGS, tagCountProp, DEFAULT_TAG_COUNT );
            }
        }
        this.tagCount = count;
    }

    /**
     * Called by the page save pipeline. Delegates to {@link #applyDefaults(String, String)}.
     */
    @Override
    public String preSave( final Context context, final String content ) throws FilterException {
        final String pageName = context.getPage().getName();
        return applyDefaults( pageName, content );
    }

    /**
     * Applies default frontmatter generation logic. Exposed as {@code public} for
     * direct testability without a {@link Context}.
     *
     * @param pageName the wiki page name
     * @param content  the raw page content
     * @return the content, potentially prepended with a generated frontmatter block
     */
    public String applyDefaults( final String pageName, final String content ) {
        if ( !enabled || isSystemPage.test( pageName ) ) {
            return content;
        }

        final ParsedPage parsed = FrontmatterParser.parse( content );
        if ( !parsed.metadata().isEmpty() ) {
            return content;
        }

        final String body = parsed.body();
        final Map< String, Object > metadata = new LinkedHashMap<>();
        metadata.put( "title", TitleDeriver.derive( pageName ) );
        metadata.put( "type", "article" );
        final List< String > tags = TagExtractor.extract( body, tagCount );
        metadata.put( "tags", tags );
        metadata.put( "summary", SummaryExtractor.extract( body ) );
        metadata.put( "auto-generated", Boolean.TRUE );

        LOG.info( "Generated default frontmatter for page '{}'", pageName );

        return FrontmatterWriter.write( metadata, body );
    }
}
