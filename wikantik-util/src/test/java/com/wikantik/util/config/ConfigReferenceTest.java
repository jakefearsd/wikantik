package com.wikantik.util.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigReferenceTest {

    private static final List<String> SAMPLE = List.of(
        "# [Ontology]",
        "#",
        "#  Master switch for the RDF/OWL ontology layer.",
        "#  Off = no TDB2 store is opened.",
        "#  Type: boolean",
        "wikantik.ontology.enabled = true",
        "",
        "#  Directory for the TDB2 store.",
        "#  Type: path",
        "#  Blank means: ${wikantik.workDir}/ontology-tdb2",
        "wikantik.ontology.tdb2.dir =",
        "",
        "#  Bearer token for SCIM.",
        "#  Type: secret",
        "#  Source: system-property",
        "wikantik.scim.token =",
        "",
        "#wikantik.old.commented = 3",
        "# wikantik.other.commented=x",
        "wikantik.no.description = 7",
        "wikantik.ontology.enabled = false"
    );

    @Test
    void parses_description_type_and_section() {
        final ConfigReference.Parsed p = ConfigReference.parse( SAMPLE, "wikantik." );
        final ConfigReference.Entry e = p.entry( "wikantik.ontology.enabled" ).orElseThrow();
        assertEquals( "true", e.value() );
        assertEquals( List.of( "Master switch for the RDF/OWL ontology layer.", "Off = no TDB2 store is opened." ), e.description() );
        assertEquals( "boolean", e.type() );
        assertEquals( "Ontology", e.section() );
        assertEquals( 6, e.line() );
        assertNull( e.blankMeans() );
        assertEquals( "properties", e.source() );
    }

    @Test
    void blank_value_and_directives() {
        final ConfigReference.Parsed p = ConfigReference.parse( SAMPLE, "wikantik." );
        final ConfigReference.Entry dir = p.entry( "wikantik.ontology.tdb2.dir" ).orElseThrow();
        assertTrue( dir.isBlank() );
        assertEquals( "${wikantik.workDir}/ontology-tdb2", dir.blankMeans() );
        final ConfigReference.Entry tok = p.entry( "wikantik.scim.token" ).orElseThrow();
        assertEquals( "system-property", tok.source() );
        assertEquals( "secret", tok.type() );
    }

    @Test
    void reports_commented_out_and_duplicate_keys() {
        final ConfigReference.Parsed p = ConfigReference.parse( SAMPLE, "wikantik." );
        assertEquals( List.of( "wikantik.old.commented", "wikantik.other.commented" ), p.commentedOutKeys() );
        assertEquals( List.of( "wikantik.ontology.enabled" ), p.duplicateKeys() );
    }

    @Test
    void entry_without_comment_block_has_no_description_or_type() {
        final ConfigReference.Entry e = ConfigReference.parse( SAMPLE, "wikantik." ).entry( "wikantik.no.description" ).orElseThrow();
        assertFalse( e.hasDescription() );
        assertFalse( e.hasType() );
    }

    @Test
    void comment_block_must_be_adjacent_to_the_key() {
        final List<String> lines = List.of( "#  Orphan comment.", "#  Type: int", "", "wikantik.x = 1" );
        final ConfigReference.Entry e = ConfigReference.parse( lines, "wikantik." ).entry( "wikantik.x" ).orElseThrow();
        assertFalse( e.hasDescription(), "a blank line breaks the block" );
    }

    @Test
    void env_override_name_keeps_case() {
        final ConfigReference.Entry e = ConfigReference.parse( List.of( "wikantik.baseURL = x" ), "wikantik." ).entry( "wikantik.baseURL" ).orElseThrow();
        assertEquals( "wikantik_baseURL", e.envOverrideName() );
    }

    @Test
    void ignores_keys_outside_prefix() {
        final ConfigReference.Parsed p = ConfigReference.parse( List.of( "log4j.rootLogger = INFO", "wikantik.a = 1" ), "wikantik." );
        assertEquals( 1, p.entries().size() );
    }

    @Test
    void colon_separated_commented_key_is_reported() {
        final ConfigReference.Parsed p = ConfigReference.parse( List.of( "#wikantik.old.colon: 5", "wikantik.live = 1" ), "wikantik." );
        assertEquals( List.of( "wikantik.old.colon" ), p.commentedOutKeys() );
        final ConfigReference.Entry live = p.entry( "wikantik.live" ).orElseThrow();
        assertFalse( live.hasDescription() );
    }

    @Test
    void bang_prefixed_commented_key_is_reported() {
        final ConfigReference.Parsed p = ConfigReference.parse( List.of( "!wikantik.old.bang = 5", "wikantik.live = 1" ), "wikantik." );
        assertEquals( List.of( "wikantik.old.bang" ), p.commentedOutKeys() );
        final ConfigReference.Entry live = p.entry( "wikantik.live" ).orElseThrow();
        assertFalse( live.hasDescription() );
    }

    @Test
    void directive_like_prose_is_not_a_commented_key() {
        final ConfigReference.Parsed p = ConfigReference.parse( List.of( "#  Note: keep this.", "#  Type: int", "wikantik.x = 1" ), "wikantik." );
        assertEquals( List.of(), p.commentedOutKeys() );
        final ConfigReference.Entry x = p.entry( "wikantik.x" ).orElseThrow();
        assertEquals( List.of( "Note: keep this." ), x.description() );
        assertEquals( "int", x.type() );
    }
}
