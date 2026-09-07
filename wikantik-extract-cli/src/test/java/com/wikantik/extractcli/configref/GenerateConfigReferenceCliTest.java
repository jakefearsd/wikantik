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

    // ------------------------------------------------------------------
    // Configuration-surface program follow-up: shrink unusable table cells, split a section's
    // own preamble out of its first key, a secrets index, per-file tables of contents, and
    // disambiguated anchors for a heading declared in two source files.
    // ------------------------------------------------------------------

    /** A genuine section preamble ("This section configures widgets globally.") separated from
     *  the first key's own description only by a "#"-only line — the wikantik.applicationName
     *  shape. */
    private static final List<String> WIDGET_LINES = List.of(
        "# [Widgets]",
        "#",
        "#  This section configures widgets globally.",
        "#",
        "#  Specific setting for the primary widget key.",
        "#  Type: string",
        "wikantik.widget.name = default"
    );

    @Test
    void a_genuine_section_preamble_is_hoisted_out_of_the_first_key_row() {
        final ConfigReference.Parsed parsed = ConfigReference.parse( WIDGET_LINES, "wikantik." );
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        final List<GenerateConfigReferenceCli.SourceFile> sources = List.of(
            new GenerateConfigReferenceCli.SourceFile( "Wikantik core settings", "path", "note", parsed, WIDGET_LINES ) );
        final String md = cli.render( sources, "ConfigurationReference.md.mustache" );

        assertTrue( md.contains( "## Widgets\n\nThis section configures widgets globally.\n\n| Key" ), md );
        final String row = md.lines().filter( l -> l.startsWith( "| `wikantik.widget.name`" ) )
            .findFirst().orElseThrow( () -> new AssertionError( md ) );
        assertTrue( row.contains( "Specific setting for the primary widget key." ), row );
        assertFalse( row.contains( "This section configures widgets globally" ),
            "the section preamble must not also appear inside the key's own row: " + row );
    }

    /** wikantik.pageProvider's shape: the first key after a section marker has a multi-paragraph
     *  block, but its own trailing paragraph is nothing but a bare {@code Type:} directive with no
     *  prose of its own — hoisting would leave the row empty, so nothing should be split here. */
    private static final List<String> PAGE_PROVIDER_LINES = List.of(
        "# [Storage]",
        "#",
        "#  Explains storage in general with several choices listed here for the reader.",
        "#",
        "#  Type: class",
        "wikantik.pageProvider = VersioningFileProvider"
    );

    @Test
    void a_first_key_whose_own_paragraph_is_directive_only_is_not_split() {
        final ConfigReference.Parsed parsed = ConfigReference.parse( PAGE_PROVIDER_LINES, "wikantik." );
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        final List<GenerateConfigReferenceCli.SourceFile> sources = List.of(
            new GenerateConfigReferenceCli.SourceFile( "Wikantik core settings", "path", "note", parsed, PAGE_PROVIDER_LINES ) );
        final String md = cli.render( sources, "ConfigurationReference.md.mustache" );

        // No preamble paragraph between the heading and the table — nothing was hoisted.
        assertTrue( md.contains( "## Storage\n\n| Key" ), md );
        final String row = md.lines().filter( l -> l.startsWith( "| `wikantik.pageProvider`" ) )
            .findFirst().orElseThrow( () -> new AssertionError( md ) );
        assertTrue( row.contains( "Explains storage in general with several choices listed here for the reader." ), row );
    }

    /** A description containing an {@code Example:} line forces the table cell to summarise
     *  even though the raw text is short, and the example renders as code rather than run-on
     *  prose in the full-description listing below the table. */
    private static final ConfigReference.Parsed EXAMPLE_DESCRIPTION = ConfigReference.parse( List.of(
        "# [Widgets]",
        "#  Explains widget syntax briefly.",
        "#  Example: wikantik.thing.name = value",
        "#  Type: string",
        "wikantik.thing.name = default"
    ), "wikantik." );

    @Test
    void a_description_with_an_example_line_is_summarised_and_listed_in_full_below_the_table() {
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        final List<GenerateConfigReferenceCli.SourceFile> sources = List.of(
            new GenerateConfigReferenceCli.SourceFile( "Wikantik core settings", "path", "note", EXAMPLE_DESCRIPTION ) );
        final String md = cli.render( sources, "ConfigurationReference.md.mustache" );

        final String row = md.lines().filter( l -> l.startsWith( "| `wikantik.thing.name`" ) )
            .findFirst().orElseThrow( () -> new AssertionError( md ) );
        assertTrue( row.endsWith( "Explains widget syntax briefly. … |" ), row );
        assertFalse( row.contains( "Example:" ), "the example must not appear inline in the table row: " + row );
        assertTrue( md.contains(
            "- **`wikantik.thing.name`** — Explains widget syntax briefly. `Example: wikantik.thing.name = value`" ), md );
    }

    /** No description at all still renders (and is not "truncated") — the empty-cell case must
     *  survive the new summarise/full split unchanged. */
    @Test
    void a_key_with_no_description_still_renders_an_empty_cell() {
        assertEquals( "", GenerateConfigReferenceCli.renderDescription( List.of() ).summary() );
        assertFalse( GenerateConfigReferenceCli.renderDescription( List.of() ).truncated() );
    }

    @Test
    void a_short_description_is_not_truncated_and_matches_the_full_text() {
        final GenerateConfigReferenceCli.DescriptionRender dr =
            GenerateConfigReferenceCli.renderDescription( List.of( "Master switch for the ontology layer." ) );
        assertEquals( "Master switch for the ontology layer.", dr.summary() );
        assertEquals( dr.summary(), dr.full() );
        assertFalse( dr.truncated() );
    }

    @Test
    void a_description_over_the_summary_cap_is_word_boundary_truncated_with_an_ellipsis() {
        final String longSentence = "This is a single very long run-on description with no punctuation "
            + "at all that just keeps going and going well past the summary length cap so the "
            + "word boundary fallback has to kick in instead of the sentence detector";
        final GenerateConfigReferenceCli.DescriptionRender dr =
            GenerateConfigReferenceCli.renderDescription( List.of( longSentence ) );
        assertTrue( dr.truncated(), dr.summary() );
        assertTrue( dr.summary().endsWith( " …" ), dr.summary() );
        assertTrue( dr.summary().length() <= 160, dr.summary() );
        assertEquals( longSentence, dr.full() );
        assertFalse( dr.summary().contains( "  " ), "no double space at the cut point: " + dr.summary() );
    }

    // --- Secrets index --------------------------------------------------

    @Test
    void secrets_index_lists_every_secret_typed_key_with_a_file_and_a_section_link() {
        final String md = new GenerateConfigReferenceCli().render( sources(), "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "## Secrets to provision before going live" ), md );
        assertTrue( md.contains( "never" ), "the secrets index must warn never to ship a value: " + md );
        assertTrue( md.contains(
            "| `wikantik.scim.token` | Wikantik core settings | [Security & passwords](#security--passwords) |" ), md );
        // the secrets heading must appear before either file's own section tables
        assertTrue( md.indexOf( "## Secrets" ) < md.indexOf( "## Ontology" ), md );
    }

    @Test
    void a_source_with_no_secret_typed_keys_renders_no_secrets_index() {
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        final List<GenerateConfigReferenceCli.SourceFile> sources = List.of(
            new GenerateConfigReferenceCli.SourceFile( "Wikantik core settings", "path", "note", ENUM_TYPE ) );
        final String md = cli.render( sources, "ConfigurationReference.md.mustache" );
        assertFalse( md.contains( "Secrets to provision" ), md );
    }

    // --- Table of contents ----------------------------------------------

    @Test
    void each_file_gets_a_table_of_contents_before_its_own_tables() {
        final String md = new GenerateConfigReferenceCli().render( sources(), "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "**Sections in this file:**\n\n- [Ontology](#ontology)" ), md );
        assertTrue( md.contains( "- [Security & passwords](#security--passwords)" ), md );
        assertTrue( md.contains( "- [MCP access](#mcp-access)" ), md );
        // the TOC for the ini file must come before its own "## Ontology" table
        assertTrue( md.indexOf( "- [Ontology](#ontology)" ) < md.indexOf( "## Ontology" ), md );
    }

    // --- Disambiguated cross-file anchors --------------------------------

    private static final ConfigReference.Parsed SHARED_HEADING_A = ConfigReference.parse( List.of(
        "# [Shared]",
        "#  First file's shared setting.",
        "#  Type: boolean",
        "wikantik.shared.a = true"
    ), "wikantik." );

    private static final ConfigReference.Parsed SHARED_HEADING_B = ConfigReference.parse( List.of(
        "# [Shared]",
        "#  Second file's shared setting.",
        "#  Type: boolean",
        "mcp.shared.b = true"
    ), "mcp." );

    @Test
    void a_heading_declared_in_two_source_files_is_disambiguated_by_source_file() {
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        final List<GenerateConfigReferenceCli.SourceFile> sources = List.of(
            new GenerateConfigReferenceCli.SourceFile( "Wikantik core settings", "path1", "note1", SHARED_HEADING_A ),
            new GenerateConfigReferenceCli.SourceFile( "MCP admin server", "path2", "note2", SHARED_HEADING_B ) );
        final String md = cli.render( sources, "ConfigurationReference.md.mustache" );

        assertTrue( md.contains( "## Shared\n" ), md );
        assertTrue( md.contains( "## Shared (MCP admin server)" ), md );
        assertTrue( md.contains( "- [Shared (MCP admin server)](#shared-mcp-admin-server)" ), md );
        final int plainHeadingCount = md.split( "## Shared\n", -1 ).length - 1;
        assertEquals( 1, plainHeadingCount, "the first occurrence must stay unqualified: " + md );
    }

    // --- MCP/tools "no override path" rule -------------------------------

    @Test
    void the_no_env_or_dash_d_override_rule_for_mcp_and_tools_keys_is_stated_once() {
        final String md = new GenerateConfigReferenceCli().render( sources(), "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "Neither `mcp.*` nor `tools.*` keys have any environment-variable or `-D` override path at all" ), md );
    }

    // --- Slug helper ------------------------------------------------------

    @Test
    void slug_matches_githubs_heading_anchor_convention_including_ampersands() {
        assertEquals( "rest-api-mcp--agent-surfaces", GenerateConfigReferenceCli.slug( "REST API, MCP & agent surfaces" ) );
        assertEquals( "sso", GenerateConfigReferenceCli.slug( "SSO" ) );
        assertEquals( "page-storage", GenerateConfigReferenceCli.slug( "Page storage" ) );
    }
}
