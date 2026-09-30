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
package com.wikantik.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.TestEngine;
import com.wikantik.WikiSession;
import com.wikantik.WikiSubsystems;
import com.wikantik.WikiSubsystemsTestFactory;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.filters.FilterManager;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.pagegraph.spine.DefaultStructuralIndexService;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import com.wikantik.pagegraph.subsystem.PageGraphWiringHelper;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystemFactory;

/**
 * {@code StructuralIndexService} needs a real, Postgres-backed persistence layer
 * ({@code DefaultStructuralIndexService} reads {@code PageCanonicalIdsDao} et al.) — a bare
 * {@code TestEngine.build()} boots with {@code wikantik.datasource} blank (deliberately, per the
 * wikantik-main test overlay), so its Page Graph subsystem never wires up. Rather than pull in
 * the whole Knowledge Graph subsystem via a JNDI-bound datasource (heavy: embeddings, entity
 * extraction, ontology…), this test wires only the Page Graph slice directly against a
 * Testcontainers Postgres, the same "subsystem isolation" pattern used by
 * {@code KnowledgeSubsystemFactoryTest}.
 */
@RequiresPostgres
class ExportServiceTest {

    private TestEngine engine;
    private WikiSubsystems subs;
    private ExportService service;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "FinanceHub", "---\ntype: hub\ncluster: finance\n---\n"
                + "# Finance\n\nSee [Roth](RothConversions) and [Math](MathPage).\n" );
        engine.saveText( "RothConversions", "---\ntype: article\ncluster: finance/retirement\nstatus: active\n"
                + "related: [FinanceHub]\n---\n"
                + "# Roth\n\n## Setup Steps\n\n![c](RothConversions/chart.png)\n" );
        engine.addAttachment( "RothConversions", "chart.png", new byte[]{ 1, 2, 3 } );
        engine.saveText( "MathPage", "---\ntype: article\ncluster: math\n---\n# Math\n" );
        engine.saveText( "SecretFinance", "---\ntype: article\ncluster: finance\n---\n"
                + "[{ALLOW view Admin}]\n# Secret\n" );

        final DefaultStructuralIndexService structuralIndex = wireStructuralIndex( engine );
        final PageManager pageManager = engine.getManager( PageManager.class );
        final AttachmentManager attachmentManager = engine.getManager( AttachmentManager.class );
        final ReferenceManager referenceManager = engine.getManager( ReferenceManager.class );

        // Assemble only the services ExportService actually reads (page().pages()/attachments(),
        // pageGraph().structuralIndexService()/referenceManager()) via the existing test factory,
        // rather than re-deriving RestServletBase.getSubsystems()'s full bridge-assembly line.
        subs = WikiSubsystemsTestFactory.builder()
                .page( new PageSubsystem.Services( pageManager, attachmentManager, null, null, null, null, null, null, referenceManager ) )
                .pageGraph( new PageGraphSubsystem.Services( structuralIndex, null, referenceManager, null, null, null, null, null, null ) )
                .build();

        // structural index rebuild is async; force it
        subs.pageGraph().structuralIndexService().rebuild();

        // A guest must really be denied SecretFinance, or the exclusion assertions below are vacuous.
        assertFalse( new PermissionFilter( engine ).canAccessQuietly( guest(), "SecretFinance", "view" ),
                "test fixture is vacuous: guest can already view SecretFinance" );

        service = ExportService.fromSubsystems( engine, subs );
    }

    @AfterEach
    void tearDown() { engine.stop(); }

    /**
     * Wires just the Page Graph subsystem's structural index against a real (Testcontainers)
     * Postgres. Mirrors the wiring {@code WikiEngine.initKnowledgeGraph} performs, minus
     * everything Knowledge-specific this test does not need — {@code TestEngine.build()} boots
     * with {@code wikantik.datasource} blank (deliberately, per the wikantik-main test overlay),
     * so a bare engine's Page Graph subsystem never wires up.
     */
    private static DefaultStructuralIndexService wireStructuralIndex( final TestEngine engine ) throws Exception {
        final DataSource dataSource = PostgresTestDb.createDataSource();
        final CoreSubsystem.Services core = CoreSubsystemBridge.fromLegacyEngine( engine );
        final PersistenceSubsystem.Services persistence = PersistenceSubsystemFactory.create(
                new PersistenceSubsystem.Deps( dataSource, core.properties() ) );
        final PageManager pageManager = engine.getManager( PageManager.class );
        final ReferenceManager referenceManager = engine.getManager( ReferenceManager.class );
        final FilterManager filterManager = engine.getManager( FilterManager.class );
        return PageGraphWiringHelper.wireStructuralSpine(
                engine.getWikiProperties(), persistence, core, pageManager, filterManager, referenceManager, engine );
    }

    private Session guest() { return WikiSession.guestSession( engine ); }

    private ExportSelection finance( final int hops ) {
        return new ExportSelection( List.of( "finance" ), true, List.of(), Optional.empty(), Optional.empty(), hops, UnresolvedLinkMode.KEEP );
    }

    @Test
    void previewCountsAndHidesRestricted() {
        final ExportPreview p = service.preview( guest(), finance( 0 ) );
        assertEquals( 2, p.pages() );
        assertEquals( 1, p.attachments() );
        assertFalse( p.sample().contains( "SecretFinance" ) );
        assertEquals( 1, p.unresolvedLinks() ); // MathPage
    }

    @Test
    void zipIsAWorkingVault() throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        service.stream( service.prepare( guest(), finance( 1 ) ), bos );
        final Map< String, byte[] > z = ObsidianVaultWriterTest.unzip( bos.toByteArray() );
        assertTrue( z.containsKey( "finance/FinanceHub.md" ) );
        assertTrue( z.containsKey( "finance/retirement/RothConversions.md" ) );
        assertTrue( z.containsKey( "math/MathPage.md" ) ); // pulled in by hops=1
        assertTrue( z.containsKey( "_attachments/RothConversions/chart.png" ) );
        assertFalse( z.keySet().stream().anyMatch( k -> k.contains( "SecretFinance" ) ) );

        final String hub = new String( z.get( "finance/FinanceHub.md" ), StandardCharsets.UTF_8 );
        assertTrue( hub.contains( "[[RothConversions|Roth]]" ), hub );

        final String roth = new String( z.get( "finance/retirement/RothConversions.md" ), StandardCharsets.UTF_8 );
        assertTrue( roth.contains( "![[chart.png]]" ), roth );
        assertTrue( roth.contains( "  - \"[[FinanceHub]]\"" ), roth );
        assertTrue( roth.contains( "wikantik_version:" ), roth );

        assertTrue( z.containsKey( ".wikantik/manifest.json" ) );
        assertTrue( z.containsKey( "Wikantik Export.md" ) );
    }

    @Test
    void overCapThrows() {
        final ExportService tiny = new ExportService( subs.pageGraph().structuralIndexService(), subs.page().pages(),
                subs.pageGraph().referenceManager(), subs.page().attachments(), new PermissionFilter( engine ),
                "https://w.example", 1, Clock.systemUTC() );
        final ExportTooLargeException e = assertThrows( ExportTooLargeException.class, () -> tiny.prepare( guest(), finance( 0 ) ) );
        assertEquals( 2, e.count() );
    }

    @Test
    void converterFailureYieldsCompleteZipWithWarning() throws Exception {
        final com.wikantik.api.managers.PageManager spy = new FailingPureTextPageManager( subs.page().pages(), "MathPage" );
        final ExportService spied = new ExportService( subs.pageGraph().structuralIndexService(), spy,
                subs.pageGraph().referenceManager(), subs.page().attachments(), new PermissionFilter( engine ),
                "https://w.example", ExportService.DEFAULT_MAX_PAGES, Clock.systemUTC() );

        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        spied.stream( spied.prepare( guest(), finance( 1 ) ), bos );
        final Map< String, byte[] > z = ObsidianVaultWriterTest.unzip( bos.toByteArray() );

        assertTrue( z.containsKey( "math/MathPage.md" ) );
        final String manifest = new String( z.get( ".wikantik/manifest.json" ), StandardCharsets.UTF_8 );
        assertTrue( manifest.contains( "MathPage" ), manifest );
        assertTrue( manifest.toLowerCase( java.util.Locale.ROOT ).contains( "warning" ) || manifest.contains( "\"warnings\": [" ),
                manifest );
    }

    @Test
    void outOfExportHeadingResolvesOnlyForViewerWithPermission() throws Exception {
        engine.saveText( "AdminOnlyPage", "---\ntype: article\ncluster: restricted\n---\n"
                + "[{ALLOW view Admin}]\n# Admin Only\n\n## Secret Note\n\nHush.\n" );
        engine.saveText( "FinanceLinksOut", "---\ntype: article\ncluster: finance\n---\n"
                + "# Finance Links Out\n\nSee [Secret](AdminOnlyPage#secret-note).\n" );
        subs.pageGraph().structuralIndexService().rebuild();

        final Session admin = engine.adminSession();
        final PermissionFilter perms = new PermissionFilter( engine );
        assertFalse( perms.canAccessQuietly( guest(), "AdminOnlyPage", "view" ),
                "test fixture is vacuous: guest can already view AdminOnlyPage" );
        assertTrue( perms.canAccessQuietly( admin, "AdminOnlyPage", "view" ),
                "test fixture is vacuous: admin cannot view AdminOnlyPage" );

        // Exporting as guest: the anchor's target is unviewable to the exporting caller, so the
        // heading never resolves — falls back to a plain (headless) wikilink per converter rules.
        final ByteArrayOutputStream guestBos = new ByteArrayOutputStream();
        service.stream( service.prepare( guest(), finance( 0 ) ), guestBos );
        final String guestPage = new String(
                ObsidianVaultWriterTest.unzip( guestBos.toByteArray() ).get( "finance/FinanceLinksOut.md" ), StandardCharsets.UTF_8 );
        assertTrue( guestPage.contains( "[[AdminOnlyPage|Secret]]" ), guestPage );
        assertFalse( guestPage.contains( "Secret Note" ), guestPage );

        // Exporting as admin: same page, same link — the exporting caller's own permission (not
        // a fixed guest fallback) now allows the heading to resolve.
        final ByteArrayOutputStream adminBos = new ByteArrayOutputStream();
        service.stream( service.prepare( admin, finance( 0 ) ), adminBos );
        final String adminPage = new String(
                ObsidianVaultWriterTest.unzip( adminBos.toByteArray() ).get( "finance/FinanceLinksOut.md" ), StandardCharsets.UTF_8 );
        assertTrue( adminPage.contains( "[[AdminOnlyPage#Secret Note|Secret]]" ), adminPage );
    }

    @Test
    void attachmentReadFailureYieldsCompleteZipWithWarning() throws Exception {
        final AttachmentManager failing = new FailingAttachmentManager( subs.page().attachments(), "chart.png" );
        final ExportService spied = new ExportService( subs.pageGraph().structuralIndexService(), subs.page().pages(),
                subs.pageGraph().referenceManager(), failing, new PermissionFilter( engine ),
                "https://w.example", ExportService.DEFAULT_MAX_PAGES, Clock.systemUTC() );

        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        spied.stream( spied.prepare( guest(), finance( 0 ) ), bos );
        final Map< String, byte[] > z = ObsidianVaultWriterTest.unzip( bos.toByteArray() );

        assertFalse( z.containsKey( "_attachments/RothConversions/chart.png" ) );
        assertTrue( z.containsKey( "finance/retirement/RothConversions.md" ) ); // the page itself is unaffected

        final String manifest = new String( z.get( ".wikantik/manifest.json" ), StandardCharsets.UTF_8 );
        assertTrue( manifest.contains( "chart.png" ), manifest );
        assertTrue( manifest.contains( "\"warnings\": [" ), manifest );

        final String readme = new String( z.get( "Wikantik Export.md" ), StandardCharsets.UTF_8 );
        assertTrue( readme.contains( "chart.png" ), readme );
    }

    @Test
    void clientDisconnectDuringStreamPropagates() throws Exception {
        final ExportService.PreparedExport prepared = service.prepare( guest(), finance( 0 ) );
        final OutputStream broken = new OutputStream() {
            @Override public void write( final int b ) throws IOException {
                throw new IOException( "broken pipe" );
            }
        };
        assertThrows( IOException.class, () -> service.stream( prepared, broken ) );
    }

    // ---- fromSubsystems( Engine, WikiSubsystems, String ) base-URL fallback --------------------
    //
    // wikantik.baseURL defaults to blank (verified: the wikantik-main test overlay does not set
    // it), which would make every wiki link written into an export relative and useless once the
    // vault is opened outside the wiki. Callers with request context (ExportResource) pass an
    // absolute, request-derived fallback; ExportService uses it only when the property is blank.

    @Test
    void fromSubsystemsUsesFallback_whenBaseUrlPropertyBlank() throws Exception {
        assertTrue( engine.getWikiProperties().getProperty( "wikantik.baseURL", "" ).isBlank(),
                "test fixture is vacuous: wikantik.baseURL is not blank by default" );

        final ExportService svc = ExportService.fromSubsystems( engine, subs, "https://fallback.example" );
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        svc.stream( svc.prepare( guest(), finance( 0 ) ), bos );
        final String manifest = new String(
                ObsidianVaultWriterTest.unzip( bos.toByteArray() ).get( ".wikantik/manifest.json" ), StandardCharsets.UTF_8 );
        assertTrue( manifest.contains( "\"serverBaseUrl\": \"https://fallback.example\"" ), manifest );
    }

    @Test
    void fromSubsystemsIgnoresFallback_whenBaseUrlPropertyConfigured() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.baseURL", "https://configured.example" );
        try {
            final ExportService svc = ExportService.fromSubsystems( engine, subs, "https://fallback.example" );
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            svc.stream( svc.prepare( guest(), finance( 0 ) ), bos );
            final String manifest = new String(
                    ObsidianVaultWriterTest.unzip( bos.toByteArray() ).get( ".wikantik/manifest.json" ), StandardCharsets.UTF_8 );
            assertTrue( manifest.contains( "\"serverBaseUrl\": \"https://configured.example\"" ), manifest );
            assertFalse( manifest.contains( "fallback.example" ), manifest );
        } finally {
            engine.getWikiProperties().setProperty( "wikantik.baseURL", "" );
        }
    }
}
