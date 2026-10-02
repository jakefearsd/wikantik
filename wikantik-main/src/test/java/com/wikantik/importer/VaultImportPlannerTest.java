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

import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.attachment.AttachmentUploadPolicy;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.ValidationCtx;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.*;

class VaultImportPlannerTest {

    private static final FakeWikiSnapshot SNAP =
        new FakeWikiSnapshot( Set.of( "Existing Page" ), Set.of( "Main", "LeftMenu" ), Map.of() );
    private static final ImportOptions FOLDERS = ImportOptions.parse( "folders", null );

    private static byte[] b( final String s ) {
        return s.getBytes( StandardCharsets.UTF_8 );
    }

    private static PlanResult plan( final Map< String, byte[] > vault, final WikiSnapshot snap, final ImportOptions o,
                                    final int maxPages ) throws Exception {
        final Path p = TestVaults.write( TestVaults.zip( vault ) );
        try {
            final VaultArchive a = new VaultArchiveReader( ImportLimits.defaults() ).read( p );
            return new VaultImportPlanner( FrontmatterSchema.defaultSchema(),
                new AttachmentGate( new AttachmentUploadPolicy( new String[ 0 ], new String[ 0 ], Long.MAX_VALUE ) ), maxPages )
                .plan( a, o, snap, "sha", "fixture.zip" );
        } finally {
            Files.deleteIfExists( p );
        }
    }

    private static PlanResult plan( final Map< String, byte[] > vault, final WikiSnapshot snap, final ImportOptions o )
            throws Exception {
        return plan( vault, snap, o, 2000 );
    }

    private static PageDraft draft( final PlanResult r, final String name ) {
        return r.drafts().stream().filter( d -> d.name().equals( name ) ).findFirst().orElseThrow();
    }

    private static void assertStatus( final PlanResult r, final String vaultPath, final AttachmentStatus s ) {
        assertEquals( s, r.plan().attachments().stream().filter( a -> a.vaultPath().equals( vaultPath ) )
            .findFirst().orElseThrow().status(), vaultPath );
    }

    @Test
    void fixtureEndToEnd() throws Exception {
        final PlanResult r = plan( TestVaults.fixture(), SNAP, FOLDERS );
        final Map< String, PlannedPage > byPath = r.plan().pages().stream().filter( p -> p.vaultPath() != null )
            .collect( toMap( PlannedPage::vaultPath, p -> p ) );
        assertEquals( PageStatus.SKIPPED_RESERVED, byPath.get( "Main.md" ).status() );
        assertEquals( "Beta v2", byPath.get( "Projects/Beta v2!.md" ).name() );
        assertTrue( byPath.get( "Projects/Projects.md" ).hub() );
        assertEquals( "projects/deep", byPath.get( "Projects/Deep/Deeper/Gamma.md" ).cluster() );
        final PageDraft welcome = draft( r, "Welcome" );
        assertTrue( welcome.body().contains( "[[Alpha|Projects/Alpha]]" ) );
        assertTrue( welcome.body().contains( "[[Beta v2|the beta]]" ) );
        assertTrue( welcome.body().contains( "[[Missing Note]]" ) );
        assertTrue( welcome.body().contains( "![[Welcome/diagram.png|300]]" ) );
        assertFalse( welcome.body().contains( "private" ) );
        assertEquals( List.of( "start", "root-tag" ), welcome.metadata().get( "tags" ) );
        final PageDraft gamma = draft( r, "Gamma" );
        assertEquals( "article", gamma.metadata().get( "type" ) );
        assertTrue( byPath.get( "Projects/Deep/Deeper/Gamma.md" ).warnings().stream().anyMatch( w -> w.startsWith( "type:" ) ) );
        final PageDraft alpha = draft( r, "Alpha" );
        assertTrue( alpha.body().contains( "[[Gamma]]" ) && !alpha.body().contains( "^b1" ) && !alpha.body().contains( "^a1" ) );
        assertTrue( alpha.body().contains( "![[evil.svg]]" ) );
        final PageDraft code = draft( r, "Code" );
        assertTrue( code.body().contains( "```\n[[NotALink]]\n```" ) && code.body().contains( "`[[AlsoNot]]`" ) );
        assertTrue( code.body().contains( "[[Alpha|alpha]]" ) && code.body().contains( "[[Beta v2|beta]]" )
            && code.body().contains( "[[Beta v2|beta2]]" ) );
        assertTrue( draft( r, "Daily" ).body().startsWith( "```yaml" ) );
        assertEquals( List.of( "Notes Hub", "Projects", "Deep Hub" ),
            r.drafts().stream().limit( 3 ).map( PageDraft::name ).toList() );
        assertStatus( r, "Projects/assets/evil.svg", AttachmentStatus.BLOCKED );
        assertStatus( r, "Projects/assets/unused.png", AttachmentStatus.SKIPPED_UNREFERENCED );
        assertStatus( r, "Projects/assets/diagram.png", AttachmentStatus.IMPORT );
        assertTrue( r.plan().warningGroups().containsKey( "link" ) );
        assertEquals( 1, r.attachmentsToImport().size() );
        assertEquals( "Welcome", r.attachmentsToImport().get( 0 ).owner() );
    }

    @Test
    void systemPageNotesAreSkippedReserved() throws Exception {
        final PlanResult r = plan( Map.of( "Main.md", b( "x" ), "LeftMenu.md", b( "y" ), "Other.md", b( "[[Main]]" ) ), SNAP, FOLDERS );
        assertEquals( 2, r.plan().totals().pagesSkippedReserved() );
        assertEquals( List.of( "Other" ), r.drafts().stream().map( PageDraft::name ).toList() );
        assertEquals( "[[Main]]", r.drafts().get( 0 ).body() );
    }

    @Test
    void existingPageSkippedAndLinksPointToIt() throws Exception {
        final PlanResult r = plan( Map.of( "existing page.md", b( "x" ), "B.md", b( "[[existing page]]" ) ), SNAP, FOLDERS );
        assertEquals( PageStatus.SKIPPED_EXISTS, r.plan().pages().stream()
            .filter( p -> "existing page.md".equals( p.vaultPath() ) ).findFirst().orElseThrow().status() );
        assertEquals( "[[existing page]]", draft( r, "B" ).body() );
        assertEquals( 1, r.plan().totals().pagesSkippedExisting() );
    }

    @Test
    void frontmatterErrorIsWillFail() throws Exception {
        final PlanResult r = plan( Map.of( "Bad.md", b( "---\naudience: robots\n---\nbody" ) ), SNAP, FOLDERS );
        final PlannedPage p = r.plan().pages().get( 0 );
        assertEquals( PageStatus.WILL_FAIL, p.status() );
        assertTrue( p.reason().startsWith( "frontmatter:" ), p.reason() );
        assertTrue( r.drafts().isEmpty() );
        assertEquals( 1, r.plan().totals().pagesFailing() );
    }

    @Test
    void maxPagesIs413() {
        final ImportLimitException e = assertThrows( ImportLimitException.class,
            () -> plan( Map.of( "A.md", b( "a" ), "B.md", b( "b" ) ), SNAP, FOLDERS, 1 ) );
        assertEquals( ImportLimits.PROP_MAX_PAGES, e.limitKey() );
    }

    @Test
    void hashChangesWhenAPlannedNameStartsExisting() throws Exception {
        final String h1 = plan( TestVaults.fixture(), SNAP, FOLDERS ).plan().planHash();
        final String h2 = plan( TestVaults.fixture(), SNAP, FOLDERS ).plan().planHash();
        final FakeWikiSnapshot later = new FakeWikiSnapshot( Set.of( "Existing Page", "Alpha" ), Set.of( "Main", "LeftMenu" ), Map.of() );
        assertEquals( h1, h2 );
        assertNotEquals( h1, plan( TestVaults.fixture(), later, FOLDERS ).plan().planHash() );
        assertNotEquals( h1, plan( TestVaults.fixture(), SNAP, ImportOptions.parse( "none", null ) ).plan().planHash() );
    }

    @Test
    void sameAttachmentNameFromTwoFoldersGetsSuffix() throws Exception {
        final Map< String, byte[] > v = new LinkedHashMap<>();
        v.put( "N.md", b( "![[a/x.png]] ![[b/x.png]]" ) );
        v.put( "a/x.png", b( "1" ) );
        v.put( "b/x.png", b( "2" ) );
        final PlanResult r = plan( v, SNAP, ImportOptions.parse( "none", null ) );
        final String body = draft( r, "N" ).body();
        assertTrue( body.contains( "![[N/x.png]]" ) && body.contains( "![[N/x 2.png]]" ), body );
    }

    @Test
    void generatedHubSummaryIsAlwaysInRange() throws Exception {
        final String longName = "L".repeat( 60 );
        final String veryLong = "V".repeat( 120 );
        final PlanResult r = plan( Map.of( "X/a.md", b( "a" ), longName + "/b.md", b( "b" ), veryLong + "/c.md", b( "c" ) ),
            SNAP, FOLDERS );
        final SchemaDrivenFrontmatterValidator validator = new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() );
        int hubs = 0;
        for ( final PageDraft d : r.drafts() ) {
            if ( !d.hub() ) {
                continue;
            }
            hubs++;
            assertEquals( "hub", d.metadata().get( "type" ) );
            assertTrue( d.metadata().get( "cluster" ) instanceof String );
            final String summary = ( String ) d.metadata().get( "summary" );
            assertTrue( summary.length() >= 50 && summary.length() <= 160, summary.length() + ": " + summary );
            assertTrue( summary.startsWith( "Notes imported from the Obsidian folder " ) );
            assertEquals( "# " + d.metadata().get( "title" ) + "\n", d.body() );
            final List< FieldViolation > v = validator.validate( d.metadata(),
                new ValidationCtx( p -> true, a -> true, Severity.WARNING, c -> true ) );
            assertTrue( v.stream().noneMatch( x -> "summary".equals( x.field() ) ), v.toString() );
        }
        assertEquals( 3, hubs );
        assertEquals( "X", draftByCluster( r, "x" ).metadata().get( "title" ) );
    }

    private static PageDraft draftByCluster( final PlanResult r, final String cluster ) {
        return r.drafts().stream().filter( d -> cluster.equals( d.metadata().get( "cluster" ) ) && d.hub() )
            .findFirst().orElseThrow();
    }

    @Test
    void hubTypeNoteThatIsTheFolderNoteStaysHub() throws Exception {
        final PlanResult r = plan( Map.of( "F/F.md", b( "---\ntype: hub\n---\nhub body" ), "F/g.md", b( "g" ), "Root.md", b( "r" ) ), SNAP, FOLDERS );
        assertEquals( "hub", draft( r, "F" ).metadata().get( "type" ) );
        assertTrue( draft( r, "F" ).hub() );
    }

    @Test
    void hubTypeNoteInAJoinedClusterIsDowngraded() throws Exception {
        final FakeWikiSnapshot snap = new FakeWikiSnapshot( Set.of( "FHub" ), Set.of(), Map.of( "f", "FHub" ) );
        final PlanResult r = plan( Map.of( "F/F.md", b( "---\ntype: hub\n---\nhub body" ), "Root.md", b( "r" ) ), snap, FOLDERS );
        assertEquals( "article", draft( r, "F" ).metadata().get( "type" ) );
        assertFalse( draft( r, "F" ).hub() );
    }

    @Test
    void failedFolderNoteIsReplacedByGeneratedHub() throws Exception {
        final PlanResult r = plan( Map.of( "F/F.md", b( "---\naudience: robots\n---\nx" ), "F/g.md", b( "g" ),
            "Root.md", b( "r" ) ), SNAP, FOLDERS );
        final Map< String, PlannedPage > byName = r.plan().pages().stream().collect( toMap( PlannedPage::name, p -> p ) );
        assertEquals( PageStatus.WILL_FAIL, byName.get( "F" ).status() );
        assertFalse( byName.get( "F" ).hub() );
        assertEquals( "f", byName.get( "g" ).cluster() );
        assertEquals( "F Hub", r.plan().clusters().get( 0 ).hubPage() );
        assertTrue( byName.containsKey( "F Hub" ) && byName.get( "F Hub" ).hub() );
        assertEquals( "f", draftByCluster( r, "f" ).metadata().get( "cluster" ) );
        assertEquals( "F Hub", draftByCluster( r, "f" ).name() );
        assertFalse( r.plan().warningGroups().containsKey( "cluster" ) );
    }

    @Test
    void failedFolderNoteFallsBackToAnotherMergedFolderNote() throws Exception {
        final PlanResult r = plan( Map.of( "My Notes/My Notes.md", b( "---\naudience: robots\n---\nx" ),
            "my-notes/my-notes.md", b( "ok" ), "Root.md", b( "r" ) ), SNAP, FOLDERS );
        assertEquals( "my-notes", r.plan().clusters().get( 0 ).hubPage() );
        assertTrue( r.drafts().stream().anyMatch( d -> d.hub() && "my-notes".equals( d.name() ) ) );
    }

    @Test
    void oversizedNoteIsWillFailNamingTheLimit() throws Exception {
        final String big = "x".repeat( ImportLimits.DEFAULT_MAX_PAGE_BYTES + 10 );
        final PlanResult r = plan( Map.of( "Big.md", b( big ), "Small.md", b( "s" ) ), SNAP, FOLDERS );
        final PlannedPage p = r.plan().pages().stream().filter( x -> "Big".equals( x.name() ) ).findFirst().orElseThrow();
        assertEquals( PageStatus.WILL_FAIL, p.status() );
        assertTrue( p.reason().contains( "wikantik.api.maxPageBytes" ) );
    }

    @Test
    void policyBlockedAttachmentCarriesTheGateReason() throws Exception {
        final Path p = TestVaults.write( TestVaults.zip( Map.of( "N.md", b( "![[big.png]]" ), "big.png", b( "0123456789" ) ) ) );
        try {
            final VaultArchive a = new VaultArchiveReader( ImportLimits.defaults() ).read( p );
            final PlanResult r = new VaultImportPlanner( FrontmatterSchema.defaultSchema(),
                new AttachmentGate( new AttachmentUploadPolicy( new String[ 0 ], new String[ 0 ], 5 ) ), 2000 )
                .plan( a, ImportOptions.parse( "none", null ), SNAP, "sha", "v.zip" );
            final PlannedAttachment pa = r.plan().attachments().get( 0 );
            assertEquals( AttachmentStatus.BLOCKED, pa.status() );
            assertTrue( pa.reason().contains( "wikantik.attachment.maxsize (5 bytes)" ) );
            assertTrue( draft( r, "N" ).body().contains( "![[big.png]]" ) );
        } finally {
            Files.deleteIfExists( p );
        }
    }

    @Test
    void duplicateBasenameInTwoFoldersBothImport() throws Exception {
        final PlanResult r = plan( Map.of( "a/Dup.md", b( "1" ), "b/Dup.md", b( "2" ), "Root.md", b( "[[a/Dup]] [[b/Dup]]" ) ),
            SNAP, ImportOptions.parse( "none", null ) );
        assertEquals( Set.of( "Dup", "Dup (b)", "Root" ), r.drafts().stream().map( PageDraft::name ).collect( java.util.stream.Collectors.toSet() ) );
        assertTrue( draft( r, "Root" ).body().contains( "[[Dup (b)|b/Dup]]" ), draft( r, "Root" ).body() );
    }

    @Test
    void linkToExistingPageWithDifferingCaseIsKeptAndNotUnresolved() throws Exception {
        final PlanResult r = plan( Map.of( "A.md", b( "[[existing PAGE]]" ), "B.md", b( "b" ) ), SNAP, FOLDERS );
        assertEquals( "[[existing PAGE]]", draft( r, "A" ).body() );   // the wiki resolves case-insensitively
        assertFalse( r.plan().warningGroups().containsKey( "link" ) );
    }
}
