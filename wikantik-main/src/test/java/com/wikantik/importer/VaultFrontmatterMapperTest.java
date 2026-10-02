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
package com.wikantik.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.wikantik.api.frontmatter.schema.FrontmatterSchema;

class VaultFrontmatterMapperTest {

    private final VaultFrontmatterMapper mapper = new VaultFrontmatterMapper( FrontmatterSchema.defaultSchema() );

    @Test
    void frontmatterRules() {
        final String note = "---\ncanonical_id: abc\nconfidence: stale\ntags: [Work, \"#team/core\"]\nalias: Old\ntype: Report\ncluster: elsewhere\nstatus: draft\n---\nBody #extra\n";
        final MappedNote m = mapper.map( note, new NoteContext( "Beta (v2) 1", "Beta (v2) #1", "projects", false ) );
        assertFalse( m.metadata().containsKey( "canonical_id" ) );
        assertFalse( m.metadata().containsKey( "confidence" ) );
        assertEquals( List.of( "work", "team-core", "extra" ), m.metadata().get( "tags" ) );
        assertEquals( List.of( "Old", "Beta (v2) #1" ), m.metadata().get( "aliases" ) );
        assertFalse( m.metadata().containsKey( "alias" ) );
        assertEquals( "Beta (v2) #1", m.metadata().get( "title" ) );
        assertFalse( m.metadata().containsKey( "type" ) );
        assertEquals( "projects", m.metadata().get( "cluster" ) );
        assertEquals( "draft", m.metadata().get( "status" ) );
        assertTrue( m.warnings().stream().anyMatch( w -> w.startsWith( "type:" ) ) );
        assertEquals( "Body #extra\n", m.body() );
    }

    @Test
    void malformedYamlBecomesCodeBlock() {
        final MappedNote m = mapper.map( "---\ntitle: a: b: [\n---\nBody\n", new NoteContext( "Daily", "Daily", null, false ) );
        assertTrue( m.body().startsWith( "```yaml\ntitle: a: b: [\n```\n\nBody" ), m.body() );
        assertTrue( m.warnings().get( 0 ).startsWith( "frontmatter:" ) );
        assertTrue( m.metadata().isEmpty() );
    }

    @Test
    void hubFlagSetsTypeAndOtherHubsDowngrade() {
        assertEquals( "hub", mapper.map( "---\ntype: article\n---\nx", new NoteContext( "H", "H", "c", true ) ).metadata().get( "type" ) );
        final MappedNote m = mapper.map( "---\ntype: Hub\n---\nx", new NoteContext( "H", "H", "c", false ) );
        assertEquals( "article", m.metadata().get( "type" ) );
        assertEquals( "type: hub downgraded to article (not this folder's hub)", m.warnings().get( 0 ) );
    }

    @Test
    void canonicalTypeKeptLowercased() {
        assertEquals( "runbook", mapper.map( "---\ntype: RunBook\n---\nx", new NoteContext( "H", "H", null, false ) ).metadata().get( "type" ) );
    }

    @Test
    void noFrontmatterYieldsEmptyMetadataForUnchangedName() {
        final MappedNote m = mapper.map( "Body", new NoteContext( "N", "N", null, false ) );
        assertTrue( m.metadata().isEmpty() );
        assertEquals( "Body", m.body() );
        assertTrue( m.warnings().isEmpty() );
    }

    @Test
    void commaAndSpaceSeparatedTagsAndNullClusterRemovesVaultCluster() {
        final MappedNote m = mapper.map( "---\ntags: a, B c\ncluster: x\n---\nx", new NoteContext( "N", "N", null, false ) );
        assertEquals( List.of( "a", "b", "c" ), m.metadata().get( "tags" ) );
        assertFalse( m.metadata().containsKey( "cluster" ) );
    }

    @Test
    void normaliseTag() {
        assertEquals( "team-core", VaultFrontmatterMapper.normaliseTag( "#Team/Core" ) );
    }

    @Test
    void scalarAliasesSplitOnCommasOnly() {
        final NoteContext ctx = new NoteContext( "N", "N", null, false );
        assertEquals( List.of( "Old Name" ), mapper.map( "---\nalias: Old Name\n---\nx", ctx ).metadata().get( "aliases" ) );
        assertEquals( List.of( "A b", "C d" ), mapper.map( "---\naliases: A b, C d\n---\nx", ctx ).metadata().get( "aliases" ) );
        assertEquals( List.of( "A b", "C" ), mapper.map( "---\naliases: [A b, C]\n---\nx", ctx ).metadata().get( "aliases" ) );
    }

    @Test
    void mapValuedTagsAreSkippedWithWarning() {
        final MappedNote m = mapper.map( "---\ntags: {a: b}\n---\nx", new NoteContext( "N", "N", null, false ) );
        assertFalse( m.metadata().containsKey( "tags" ) );
        assertTrue( m.warnings().stream().anyMatch( w -> w.startsWith( "frontmatter:" ) ), m.warnings().toString() );
    }
}
