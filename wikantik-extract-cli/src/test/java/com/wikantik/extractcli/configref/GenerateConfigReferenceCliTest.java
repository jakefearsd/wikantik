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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** Fix round 1 — IMPORTANT 1: an enum Type value containing `|` must not corrupt the table. */
    private static final ConfigReference.Parsed ENUM_TYPE = ConfigReference.parse( List.of(
        "# [Modes]",
        "#  Example mode used only to test Type-column pipe escaping.",
        "#  Type: enum(standard|code)",
        "wikantik.example.mode = standard"
    ), "wikantik." );

    /** Fix round 1 — MINOR 1: a description containing a literal `|` must render escaped too. */
    private static final ConfigReference.Parsed PIPE_DESCRIPTION = ConfigReference.parse( List.of(
        "# [Misc]",
        "#  Accepts a|b|c style values, pipe-separated.",
        "#  Type: string",
        "wikantik.example.piped = a"
    ), "wikantik." );

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
        assertTrue( md.contains( "| `wikantik.ontology.enabled` | `boolean` | `true` | `wikantik_ontology_enabled` | Master switch for the ontology layer. |" ), md );
        assertTrue( md.contains( "| `wikantik.ontology.tdb2.dir` | `path` | *(blank: <workDir>/ontology-tdb2)* |" ), md );
        assertTrue( md.contains( "## MCP access" ) );
        assertTrue( md.contains( "`mcp.access.allowUnrestricted`" ) );
    }

    @Test
    void system_property_sourced_keys_are_flagged() {
        final String md = new GenerateConfigReferenceCli().render( sources(), "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "`wikantik.scim.token` | `secret` | *(blank)* | `-Dwikantik.scim.token` only |" ), md );
    }

    @Test
    void enum_type_pipes_are_escaped_in_the_type_column() {
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        final List<GenerateConfigReferenceCli.SourceFile> sources = List.of(
            new GenerateConfigReferenceCli.SourceFile( "Wikantik core settings",
                "wikantik-main/src/main/resources/ini/wikantik.properties",
                "the primary settings surface described above", ENUM_TYPE ) );
        final String md = cli.render( sources, "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "| `enum(standard\\|code)` |" ), md );

        final String row = md.lines()
            .filter( l -> l.startsWith( "| `wikantik.example.mode`" ) )
            .findFirst()
            .orElseThrow( () -> new AssertionError( "row not found in:\n" + md ) );
        final long unescapedPipes = java.util.regex.Pattern.compile( "(?<!\\\\)\\|" ).matcher( row ).results().count();
        assertEquals( 6, unescapedPipes, "expected 5 cell separators after the leading one, got row: " + row );
    }

    @Test
    void description_containing_a_literal_pipe_is_escaped() {
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        final List<GenerateConfigReferenceCli.SourceFile> sources = List.of(
            new GenerateConfigReferenceCli.SourceFile( "Wikantik core settings",
                "wikantik-main/src/main/resources/ini/wikantik.properties",
                "the primary settings surface described above", PIPE_DESCRIPTION ) );
        final String md = cli.render( sources, "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "Accepts a\\|b\\|c style values, pipe-separated." ), md );
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

    /** Fix round 2 (F1/I2): MCP and tools sources load ONLY from the jar-bundled file overlaid by
     *  a same-named file in tomcat/lib/ (McpConfig/ToolsConfig) — never from env, -D, or
     *  wikantik-custom.properties — so their rows must not claim an env-override name. */
    private static final ConfigReference.Parsed TOOLS = ConfigReference.parse( List.of(
        "# [Rate limiting]",
        "#  Global request budget across all clients.",
        "#  Type: int",
        "tools.ratelimit.global = 100"
    ), "tools." );

    @Test
    void mcp_and_tools_sources_render_a_fixed_override_note_instead_of_an_env_var_name() {
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        final List<GenerateConfigReferenceCli.SourceFile> sources = List.of(
            new GenerateConfigReferenceCli.SourceFile( "OpenAPI tools server",
                "wikantik-tools/src/main/resources/wikantik-tools.properties",
                "bundled in the wikantik-tools jar, overlaid by a same-named file in `tomcat/lib/`",
                TOOLS, "`tomcat/lib/wikantik-tools.properties`" ) );
        final String md = cli.render( sources, "ConfigurationReference.md.mustache" );
        assertFalse( md.contains( "tools_ratelimit_global" ), md );
        assertTrue( md.contains( "`tomcat/lib/wikantik-tools.properties`" ), md );
    }

    @Test
    void a_source_without_a_fixed_override_note_still_renders_the_env_var_name() {
        final String md = new GenerateConfigReferenceCli().render( sources(), "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "`wikantik_ontology_enabled`" ), md );
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
