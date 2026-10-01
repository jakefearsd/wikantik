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
package com.wikantik.api.frontmatter.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in page templates, one per {@code type} in {@link FrontmatterSchema}. Versioned with the schema so a
 * template can never be invalid (pinned by {@code PageTemplatesValidityTest}). Bodies use the {@code {{title}}}
 * placeholder; the client fills it and adds {@code date} and {@code cluster} to the metadata.
 */
public final class PageTemplates {

    public record Template( String type, String label, String description,
                            Map< String, Object > metadata, String body ) {
        public Template {
            metadata = java.util.Collections.unmodifiableMap( new LinkedHashMap<>( metadata ) );
        }
    }

    private static final List< Template > ALL = List.of(
            new Template( "article", "Article", "A general page: an explanation, guide or essay.",
                    meta( "article" ), "# {{title}}\n\n" ),
            new Template( "reference", "Reference", "Facts to look up: definitions, specifications, lists.",
                    meta( "reference" ), "# {{title}}\n\n## Summary\n\n## Details\n\n## See also\n" ),
            new Template( "design", "Design", "A proposal or decision record for something to be built.",
                    meta( "design" ),
                    "# {{title}}\n\n## Context\n\n## Goals\n\n## Non-goals\n\n## Design\n\n"
                            + "## Alternatives considered\n\n## Status\n" ),
            new Template( "runbook", "Runbook", "A step-by-step procedure for an operational task.",
                    runbookMeta(), "# {{title}}\n\n## Notes\n" ),
            new Template( "hub", "Hub", "The entry page that declares and introduces a cluster.",
                    meta( "hub" ), "# {{title}}\n\n## Overview\n\n## Start here\n" ) );

    private PageTemplates() {}

    public static List< Template > all() {
        return ALL;
    }

    public static Optional< Template > forType( final String type ) {
        return ALL.stream().filter( t -> t.type().equals( type ) ).findFirst();
    }

    private static Map< String, Object > meta( final String type ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "type", type );
        m.put( "status", "active" );
        return m;
    }

    private static Map< String, Object > runbookMeta() {
        final Map< String, Object > m = meta( "runbook" );
        final Map< String, Object > block = new LinkedHashMap<>();
        block.put( "when_to_use", List.of( "Describe the situation that calls for this runbook." ) );
        block.put( "steps", List.of( "First step.", "Second step." ) );
        block.put( "pitfalls", List.of( "(none known)" ) );
        m.put( "runbook", block );
        return m;
    }
}
