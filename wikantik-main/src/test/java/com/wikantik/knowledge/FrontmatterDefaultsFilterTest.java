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

import com.wikantik.api.frontmatter.FrontmatterParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterDefaultsFilterTest {

    private static final String BODY =
        "Wikantik is a powerful wiki engine built on modern Java technologies. " +
        "It supports Markdown, YAML frontmatter, and a rich plugin ecosystem. " +
        "You can extend it with custom filters, providers, and REST endpoints.";

    private FrontmatterDefaultsFilter filterWithDefaults() {
        final Properties props = new Properties();
        props.setProperty( FrontmatterDefaultsFilter.PROP_AUTO_DEFAULTS, "true" );
        return new FrontmatterDefaultsFilter( name -> name.startsWith( "System/" ), props );
    }

    @Test
    void injectsDefaultFrontmatterWhenNonePresent() {
        final FrontmatterDefaultsFilter filter = filterWithDefaults();
        final String result = filter.applyDefaults( "MyWikiPage", BODY );

        final Map< String, Object > meta = FrontmatterParser.parse( result ).metadata();
        assertFalse( meta.isEmpty(), "Frontmatter should have been injected" );
        assertEquals( "My Wiki Page", meta.get( "title" ) );
        assertEquals( "article", meta.get( "type" ) );
        assertEquals( Boolean.TRUE, meta.get( "auto-generated" ) );
        assertNotNull( meta.get( "tags" ), "tags should be present" );
        assertNotNull( meta.get( "summary" ), "summary should be present" );
        assertFalse( ( (String) meta.get( "summary" ) ).isBlank(), "summary should not be blank" );
    }

    @Test
    void passesThroughWhenFrontmatterExists() {
        final FrontmatterDefaultsFilter filter = filterWithDefaults();
        final String content = "---\ntype: hub\n---\n" + BODY;

        final String result = filter.applyDefaults( "MyWikiPage", content );
        assertEquals( content, result, "Content with existing frontmatter must be returned unchanged" );
    }

    @Test
    void passesThroughForSystemPages() {
        final FrontmatterDefaultsFilter filter = filterWithDefaults();
        final String result = filter.applyDefaults( "System/SideBar", BODY );
        assertEquals( BODY, result, "System pages must be returned unchanged" );
    }

    @Test
    void respectsConfiguredTagCount() {
        final Properties props = new Properties();
        props.setProperty( FrontmatterDefaultsFilter.PROP_AUTO_DEFAULTS, "true" );
        props.setProperty( "wikantik.frontmatter.defaultTags", "1" );
        final FrontmatterDefaultsFilter filter = new FrontmatterDefaultsFilter( name -> false, props );

        final String result = filter.applyDefaults( "MyWikiPage", BODY );
        final Map< String, Object > meta = FrontmatterParser.parse( result ).metadata();

        @SuppressWarnings( "unchecked" )
        final java.util.List< String > tags = (java.util.List< String >) meta.get( "tags" );
        assertNotNull( tags );
        assertEquals( 1, tags.size(), "Only 1 tag should be extracted when defaultTags=1" );
    }
}
