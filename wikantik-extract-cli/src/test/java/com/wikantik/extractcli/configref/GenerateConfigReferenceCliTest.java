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
package com.wikantik.extractcli.configref;

import com.wikantik.util.config.ConfigReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateConfigReferenceCliTest {

    private static final ConfigReference.Parsed WIKANTIK = ConfigReference.parse( List.of(
        "# [Ontology]",
        "#  Master switch for the ontology layer.",
        "#  Type: boolean",
        "wikantik.ontology.enabled = true",
        "#  Directory for the TDB2 store.",
        "#  Type: path",
        "#  Blank means: <workDir>/ontology-tdb2",
        "wikantik.ontology.tdb2.dir =",
        "# [Security & passwords]",
        "#  SCIM bearer token.",
        "#  Type: secret",
        "#  Source: system-property",
        "wikantik.scim.token ="
    ), "wikantik." );

    private static final ConfigReference.Parsed MCP = ConfigReference.parse( List.of(
        "# [MCP access]",
        "#  Allow unauthenticated MCP calls.",
        "#  Type: boolean",
        "mcp.access.allowUnrestricted = false"
    ), "mcp." );

    /** Two `# [Ontology]` markers in one file, entries appended between/after them (Task 12 addendum). */
    private static final ConfigReference.Parsed DUPLICATE_SECTION = ConfigReference.parse( List.of(
        "# [Ontology]",
        "#  First ontology switch.",
        "#  Type: boolean",
        "wikantik.ontology.enabled = true",
        "# [Other]",
        "#  Unrelated key placed between the two Ontology blocks.",
        "#  Type: boolean",
        "wikantik.other.flag = false",
        "# [Ontology]",
        "#  Second ontology setting, added later near a related key.",
        "#  Type: int",
        "wikantik.ontology.batchSize = 10"
    ), "wikantik." );

    private static List<GenerateConfigReferenceCli.SourceFile> sources() {
        return List.of(
            new GenerateConfigReferenceCli.SourceFile( "Wikantik core settings",
                "wikantik-main/src/main/resources/ini/wikantik.properties",
                "the primary settings surface described above", WIKANTIK ),
            new GenerateConfigReferenceCli.SourceFile( "MCP admin server",
                "wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties",
                "bundled in the wikantik-admin-mcp jar, overlaid by a same-named file in `tomcat/lib/`", MCP )
        );
    }

    @Test
    void renders_one_table_per_section_with_key_type_default_env_and_description() {
        final String md = new GenerateConfigReferenceCli().render( sources(), "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "## Ontology" ) );
        assertTrue( md.contains( "| `wikantik.ontology.enabled` | boolean | `true` | `wikantik_ontology_enabled` | Master switch for the ontology layer. |" ), md );
        assertTrue( md.contains( "| `wikantik.ontology.tdb2.dir` | path | *(blank: <workDir>/ontology-tdb2)* |" ), md );
        assertTrue( md.contains( "## MCP access" ) );
        assertTrue( md.contains( "`mcp.access.allowUnrestricted`" ) );
    }

    @Test
    void system_property_sourced_keys_are_flagged() {
        final String md = new GenerateConfigReferenceCli().render( sources(), "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "`wikantik.scim.token` | secret | *(blank)* | `-Dwikantik.scim.token` only |" ), md );
    }

    @Test
    void precedence_section_is_present_and_accurate() {
        final String md = new GenerateConfigReferenceCli().render( sources(), "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "## How a value is resolved" ) );
        assertTrue( md.contains( "wikantik-custom.properties" ) );
        assertTrue( md.contains( "wikantik.custom.cascade." ) );
        assertTrue( md.contains( "exact case" ), "env override names are case-sensitive — the doc must say so" );
    }

    @Test
    void wiki_variant_has_frontmatter_for_the_development_cluster() {
        final String md = new GenerateConfigReferenceCli().render( sources(), "WikantikConfigurationReference.md.mustache" );
        assertTrue( md.startsWith( "---\n" ) );
        assertTrue( md.contains( "cluster: wikantik-development" ) );
        assertTrue( md.contains( "canonical_id: " ) );
        assertTrue( md.contains( "type: article" ) );
    }

    @Test
    void output_is_deterministic() {
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        assertEquals( cli.render( sources(), "ConfigurationReference.md.mustache" ), cli.render( sources(), "ConfigurationReference.md.mustache" ) );
    }

    @Test
    void duplicate_section_markers_in_one_file_merge_into_a_single_heading() {
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        final List<GenerateConfigReferenceCli.SourceFile> sources = List.of(
            new GenerateConfigReferenceCli.SourceFile( "Wikantik core settings",
                "wikantik-main/src/main/resources/ini/wikantik.properties",
                "the primary settings surface described above", DUPLICATE_SECTION ) );
        final String md = cli.render( sources, "ConfigurationReference.md.mustache" );
        final int headingCount = md.split( "## Ontology", -1 ).length - 1;
        assertEquals( 1, headingCount, md );
        assertTrue( md.contains( "`wikantik.ontology.enabled`" ), md );
        assertTrue( md.contains( "`wikantik.ontology.batchSize`" ), md );
        assertTrue( md.contains( "## Other" ), md );
    }
}
