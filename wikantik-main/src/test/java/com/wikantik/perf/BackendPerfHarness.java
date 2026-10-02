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
package com.wikantik.perf;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CachingManager;
import com.wikantik.content.PageRenamer;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.importer.AttachmentGate;
import com.wikantik.importer.EngineImportPageSink;
import com.wikantik.importer.EngineWikiSnapshot;
import com.wikantik.importer.ImportLimits;
import com.wikantik.importer.ImportOptions;
import com.wikantik.importer.ImportPageSink;
import com.wikantik.importer.ItemStatus;
import com.wikantik.importer.JobView;
import com.wikantik.importer.PlanResult;
import com.wikantik.importer.SpooledUpload;
import com.wikantik.importer.TestVaults;
import com.wikantik.importer.VaultImportJob;
import com.wikantik.importer.VaultImportPlanner;
import com.wikantik.importer.VaultImportService;
import com.wikantik.pagegraph.references.DefaultReferenceManager;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.markdown.MarkdownRenderer;
import com.wikantik.ui.CommandResolver;
import com.wikantik.wikilink.WikiLinkResolver;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Reproducible measurement harness for the backend hot paths of native wikilinks, embeds, the reference scan,
 * rename and Obsidian import. Deliberately NOT a {@code *Test} class, so surefire never runs it in the normal
 * suite. Run it with {@code bin/perf-harness.sh [scenario...]} (compiles test classes, then launches this main on
 * the test classpath; pass {@code JFR=1} to record a flight recording per scenario).
 *
 * <p>Scenarios: {@code render}, {@code embed}, {@code htmlcache}, {@code refscan}, {@code rename}, {@code import},
 * {@code resolver}. Results are printed and appended to {@code target/perf/results.txt}.</p>
 */
public final class BackendPerfHarness {

    private static final Path OUT = Path.of( "target", "perf", "results.txt" );
    private static final Map< String, AtomicLong > COUNTS = new ConcurrentHashMap<>();
    private static String label = System.getProperty( "perf.label", "run" );

    private BackendPerfHarness() {
    }

    public static void main( final String[] args ) {
        int status = 0;
        try {
            run( args );
        } catch ( final Throwable t ) { // NOPMD - report and exit non-zero; stopped engines leave non-daemon threads
            t.printStackTrace();
            status = 1;
        }
        System.exit( status );
    }

    static void run( final String[] args ) throws Exception {
        final List< String > scenarios = args.length == 0
                ? List.of( "resolver", "render", "embed", "htmlcache", "rename", "refscan", "import" ) : Arrays.asList( args );
        Files.createDirectories( OUT.getParent() );
        for ( final String s : scenarios ) {
            COUNTS.clear();
            final long t0 = System.nanoTime();
            switch ( s ) {
                case "render" -> render();
                case "embed" -> embed();
                case "htmlcache" -> htmlCache();
                case "refscan" -> refScan();
                case "rename" -> rename();
                case "import" -> importVault();
                case "resolver" -> resolver();
                default -> throw new IllegalArgumentException( "unknown scenario " + s );
            }
            report( s, "scenario.wall_ms", ms( System.nanoTime() - t0 ) );
        }
    }

    // ---- scenarios ---------------------------------------------------------------------------------------------

    /** (a) A page with 500 [[ ]] links: exact, case-insensitive, title/alias, missing and attachments. */
    static void render() throws Exception {
        final TestEngine engine = markdownEngine();
        try {
            awaitIndex( engine );
            for ( int i = 0; i < 300; i++ ) {
                engine.saveText( "PerfTarget" + i, "---\ntitle: Perf Title " + i + "\naliases: [perf alias " + i + "]\n---\nbody " + i + "\n" );
            }
            engine.saveText( "PerfFiles", "files" );
            engine.saveText( "PerfHost", "host" );
            for ( int j = 0; j < 25; j++ ) {
                attach( engine, "PerfFiles", "doc" + j + ".pdf" );
                attach( engine, "PerfHost", "own" + j + ".pdf" );
            }
            final String body = fiveHundredLinks();
            engine.saveText( "PerfHost", body );
            installCounters( engine );
            final RenderingManager rm = engine.getManager( RenderingManager.class );
            final Page host = engine.getManager( PageManager.class ).getPage( "PerfHost" );
            final Supplier< Context > ctx = () -> {
                final Context c = Wiki.context().create( engine, host );
                c.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() ); // bypass render caches: measure a real render
                return c;
            };
            timed( "render500", 30, 200, () -> rm.textToHTML( ctx.get(), body ) );
            COUNTS.clear();
            final String html = rm.textToHTML( ctx.get(), body );
            report( "render500", "html_chars", html.length() );
            report( "render500", "links_rendered", count( html, "<a " ) );
            reportCounts( "render500.per_render" );
            // the scan path (save-time) for the same body
            final DefaultReferenceManager refs = ( DefaultReferenceManager ) engine.getManager( ReferenceManager.class );
            COUNTS.clear();
            timed( "scan500", 30, 200, () -> refs.scanWikiLinks( host, body ) );
        } finally {
            engine.stop();
        }
    }

    /** (b) A page that embeds 25 sizeable pages, rendered as a page view (document cache on). */
    static void embed() throws Exception {
        final TestEngine engine = markdownEngine();
        try {
            awaitIndex( engine );
            final StringBuilder host = new StringBuilder( "# Host\n\nintro\n\n" );
            for ( int i = 0; i < 25; i++ ) {
                engine.saveText( "EmbedTarget" + i, sizeablePage( i, 8_000 ) );
                host.append( "![[EmbedTarget" ).append( i ).append( "]]\n\n" );
            }
            engine.saveText( "EmbedHost", host.toString() );
            installCounters( engine );
            final RenderingManager rm = engine.getManager( RenderingManager.class );
            final Page page = engine.getManager( PageManager.class ).getPage( "EmbedHost" );
            final String text = host.toString();
            final long first = timeOnce( () -> rm.textToHTML( viewContext( engine, page ), text ) );
            report( "embed25", "first_view_ms", ms( first ) );
            timed( "embed25.view", 10, 50, () -> rm.textToHTML( viewContext( engine, page ), text ) );
            COUNTS.clear();
            final String html = rm.textToHTML( viewContext( engine, page ), text );
            report( "embed25", "html_chars", html.length() );
            report( "embed25", "embeds_rendered", count( html, "class=\"wiki-embed\"" ) );
            reportCounts( "embed25.per_view" );
        } finally {
            engine.stop();
        }
    }

    /** (b') What CACHE_HTML buys on a normal (non-embedding) page view: off (shipped default) vs registered. */
    static void htmlCache() throws Exception {
        final TestEngine engine = markdownEngine();
        try {
            awaitIndex( engine );
            for ( int i = 0; i < 60; i++ ) {
                engine.saveText( "CacheTarget" + i, "target " + i );
            }
            final String text = sizeablePage( 7, 12_000 ) + "\n\n" + linksTo( "CacheTarget", 60 );
            engine.saveText( "CachePage", text );
            final RenderingManager rm = engine.getManager( RenderingManager.class );
            final Page page = engine.getManager( PageManager.class ).getPage( "CachePage" );
            final CachingManager caches = engine.getManager( CachingManager.class );
            report( "htmlcache", "enabled_by_default", caches.enabled( CachingManager.CACHE_HTML ) ? 1 : 0 );
            timed( "htmlcache.off.view", 50, 500, () -> rm.textToHTML( viewContext( engine, page ), text ) );
            if ( !caches.enabled( CachingManager.CACHE_HTML ) ) {
                final Method register = caches.getClass().getDeclaredMethod( "registerCache", String.class );
                register.setAccessible( true );
                register.invoke( caches, CachingManager.CACHE_HTML );
            }
            timed( "htmlcache.on.view", 50, 500, () -> rm.textToHTML( viewContext( engine, page ), text ) );
            timed( "htmlcache.none.render", 20, 200, () -> {
                final Context c = Wiki.context().create( engine, page );
                c.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );
                return rm.textToHTML( c, text );
            } );
        } finally {
            engine.stop();
        }
    }

    /** (c) Startup over ~2,000 pages with [[ ]] links, then explicit native rescans. */
    static void refScan() throws Exception {
        final int n = Integer.getInteger( "perf.pages", 2000 );
        final Path root = Path.of( "target", "perf-refscan-" + System.currentTimeMillis() ).toAbsolutePath();
        final Path pageDir = Files.createDirectories( root.resolve( "pages" ) );
        for ( int i = 0; i < n; i++ ) {
            Files.writeString( pageDir.resolve( "ScanPage" + i + ".md" ), scanPage( i, n ), StandardCharsets.UTF_8 );
        }
        final Properties p = TestEngine.getTestProperties();
        p.setProperty( "wikantik.test.disable-clean-props", "true" );
        p.setProperty( "wikantik.login.throttling", "false" );
        p.setProperty( "wikantik.fileSystemProvider.pageDir", pageDir.toString() );
        p.setProperty( "wikantik.basicAttachmentProvider.storageDir", root.resolve( "att" ).toString() );
        p.setProperty( "wikantik.workDir", root.resolve( "work" ).toString() );
        markdown( p );
        final long t0 = System.nanoTime();
        final TestEngine engine = TestEngine.build( p );
        final long started = System.nanoTime() - t0;
        try {
            report( "refscan", "engine_start_ms", ms( started ) );
            awaitIndex( engine );
            report( "refscan", "start_to_index_ready_ms", ms( System.nanoTime() - t0 ) );
            final DefaultReferenceManager refs = ( DefaultReferenceManager ) engine.getManager( ReferenceManager.class );
            installCounters( engine );
            timed( "refscan.rescanNativeWikiLinks", 1, 3, () -> {
                refs.rescanNativeWikiLinks();
                return null;
            } );
            reportCounts( "refscan.per_3_rescans" );
            report( "refscan", "referrers_of_ScanPage0", refs.findReferrers( "ScanPage0" ).size() );
            // (d') rename inside the 2,000-page corpus: ScanPage0 is referenced by every tenth page
            final PageManager pm = engine.getManager( PageManager.class );
            final Context c = Wiki.context().create( engine, pm.getPage( "ScanPage0" ) );
            final long ns = timeOnce( () -> engine.getManager( PageRenamer.class ).renamePage( c, "ScanPage0", "ScanPageZero", true ) );
            report( "refscan.rename", "ms", ms( ns ) );
            report( "refscan.rename", "referrers_after", refs.findReferrers( "ScanPageZero" ).size() );
            final int[] k = { 1 };
            timed( "refscan.save", 5, 50, () -> {
                engine.saveText( "ScanPage" + k[ 0 ], scanPage( k[ 0 ]++, n ) + "\nedited\n" );
                return null;
            } );
        } finally {
            engine.stop();
        }
    }

    /** (d) Rename of a page referenced by 200 pages. */
    static void rename() throws Exception {
        final TestEngine engine = markdownEngine();
        try {
            awaitIndex( engine );
            engine.saveText( "RenameTarget", "---\ntitle: Rename Target Title\n---\ntarget body\n" );
            for ( int i = 0; i < 200; i++ ) {
                engine.saveText( "Referrer" + i, "# R" + i + "\n\nSee [[RenameTarget]] and [[RenameTarget#Intro|the intro]], also "
                        + "[[renametarget]] and ![[RenameTarget]] plus [[Referrer" + ( ( i + 1 ) % 200 ) + "]].\n\n"
                        + "lorem ipsum ".repeat( 150 ) + "\n" );
            }
            final PageRenamer renamer = engine.getManager( PageRenamer.class );
            final PageManager pm = engine.getManager( PageManager.class );
            String from = "RenameTarget";
            String to = "RenamedTargetA";
            for ( int round = 0; round < 3; round++ ) {
                final Context c = Wiki.context().create( engine, pm.getPage( from ) );
                final String f = from;
                final String t = to;
                final long ns = timeOnce( () -> renamer.renamePage( c, f, t, true ) );
                report( "rename200", "round" + round + "_ms", ms( ns ) );
                from = to;
                to = round % 2 == 0 ? "RenamedTargetB" : "RenamedTargetA";
            }
            report( "rename200", "referrers_after", engine.getManager( ReferenceManager.class ).findReferrers( from ).size() );
        } finally {
            engine.stop();
        }
    }

    /** (e) Obsidian import: plan time and apply throughput for a 2,000-note vault. */
    static void importVault() throws Exception {
        final int n = Integer.getInteger( "perf.notes", 2000 );
        final byte[] zip = TestVaults.zipText( vault( n ) );
        report( "import", "zip_bytes", zip.length );
        final TestEngine engine = markdownEngine();
        try {
            awaitIndex( engine );
            final PageManager pm = engine.getManager( PageManager.class );
            final ImportPageSink sink = new EngineImportPageSink( engine, pm, engine.getManager( AttachmentManager.class ),
                    new PageSaveHelper( engine, pm ) );
            final ImportLimits limits = ImportLimits.defaults();
            final VaultImportPlanner planner = new VaultImportPlanner( FrontmatterSchema.defaultSchema(),
                    AttachmentGate.fromProperties( new Properties() ), limits.maxPages() );
            final VaultImportService svc = new VaultImportService( limits, planner,
                    () -> EngineWikiSnapshot.capture( pm, CoreSubsystemBridge.fromLegacyEngine( engine ).systemPageRegistry(),
                            engine.getManager( StructuralIndexService.class ) ), sink );
            final SpooledUpload up = TestVaults.upload( zip, "perf-vault.zip" );
            final PlanResult[] plan = new PlanResult[ 1 ];
            for ( int w = 0; w < 2; w++ ) {
                svc.plan( up, ImportOptions.parse( "folders", null ) ); // warm-up
            }
            final long planNs = timeOnce( () -> plan[ 0 ] = svc.plan( up, ImportOptions.parse( "folders", null ) ) );
            report( "import", "plan_ms", ms( planNs ) );
            report( "import", "planned_pages", plan[ 0 ].drafts().size() );
            installCounters( engine );
            final VaultImportJob job = svc.newJob( "perf", "admin", "admin", up, plan[ 0 ], () -> true );
            final long applyNs = timeOnce( () -> {
                job.run();
                return null;
            } );
            final JobView v = job.view();
            final long created = v.results().stream().filter( r -> r.status() == ItemStatus.CREATED ).count();
            report( "import", "apply_ms", ms( applyNs ) );
            report( "import", "created", created );
            report( "import", "failed", v.results().stream().filter( r -> r.status() == ItemStatus.FAILED ).count() );
            report( "import", "pages_per_s", created * 1e9 / applyNs );
            reportCounts( "import.apply" );
        } finally {
            engine.stop();
        }
    }

    /** (f) WikiLinkResolver: forEngine per call, title-index fold, and fold invalidation after a save. */
    static void resolver() throws Exception {
        final TestEngine engine = markdownEngine();
        try {
            awaitIndex( engine );
            for ( int i = 0; i < 2000; i++ ) {
                engine.saveText( "ResPage" + i, "---\ntitle: Res Title " + i + "\n---\nx\n" );
            }
            final String[] targets = new String[ 1000 ];
            for ( int i = 0; i < targets.length; i++ ) {
                targets[ i ] = switch ( i % 4 ) {
                    case 0 -> "ResPage" + i;
                    case 1 -> "respage" + i;
                    case 2 -> "Res Title " + i;
                    default -> "Nothing Here " + i;
                };
            }
            final WikiLinkResolver shared = WikiLinkResolver.forEngine( engine );
            timed( "resolver.shared.1000", 20, 200, () -> {
                for ( final String t : targets ) {
                    shared.resolve( t );
                }
                return null;
            } );
            timed( "resolver.forEnginePerCall.1000", 20, 200, () -> {
                for ( final String t : targets ) {
                    WikiLinkResolver.forEngine( engine ).resolve( t );
                }
                return null;
            } );
            // one save invalidates the projection: the next resolve of a non-exact target re-builds title index + fold
            final int[] k = { 0 };
            timed( "resolver.afterSave.firstNonExact", 5, 50, () -> {
                engine.saveText( "ResPage" + ( k[ 0 ]++ % 2000 ), "---\ntitle: Res Title X" + k[ 0 ] + "\n---\nx\n" );
                final long t0 = System.nanoTime();
                shared.resolve( "respage17" );
                return System.nanoTime() - t0;
            } );
        } finally {
            engine.stop();
        }
    }

    // ---- content generators ------------------------------------------------------------------------------------

    static String fiveHundredLinks() {
        final List< String > links = new ArrayList<>();
        for ( int i = 0; i < 200; i++ ) {
            links.add( "[[PerfTarget" + ( i % 300 ) + "]]" );
        }
        for ( int i = 0; i < 100; i++ ) {
            links.add( "[[perftarget" + ( i * 3 % 300 ) + "|case " + i + "]]" );
        }
        for ( int i = 0; i < 50; i++ ) {
            links.add( "[[Perf Title " + ( i * 7 % 300 ) + "]]" );
        }
        for ( int i = 0; i < 50; i++ ) {
            links.add( "[[perf alias " + ( i * 11 % 300 ) + "|alias " + i + "]]" );
        }
        for ( int i = 0; i < 50; i++ ) {
            links.add( "[[No Such Perf Page " + i + "]]" );
        }
        for ( int i = 0; i < 25; i++ ) {
            links.add( "[[PerfFiles/doc" + i + ".pdf]]" );
            links.add( "[[own" + i + ".pdf]]" );
        }
        java.util.Collections.shuffle( links, new java.util.Random( 42 ) );
        final StringBuilder sb = new StringBuilder( "# Links\n\n" );
        for ( int i = 0; i < links.size(); i++ ) {
            sb.append( "Some words around " ).append( links.get( i ) ).append( ' ' );
            if ( i % 10 == 9 ) {
                sb.append( "\n\n" );
            }
        }
        return sb.toString();
    }

    static String sizeablePage( final int seed, final int chars ) {
        final StringBuilder sb = new StringBuilder( "---\ntitle: Sizeable " + seed + "\n---\n# Page " + seed + "\n\n" );
        int s = 0;
        while ( sb.length() < chars ) {
            sb.append( "## Section " ).append( s ).append( "\n\n" )
              .append( "Paragraph with **bold**, _emphasis_, `code` and a [link](Main) about topic " ).append( s )
              .append( ". " ).append( "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat( 4 ) ).append( "\n\n" )
              .append( "- item one\n- item two\n- item three\n\n" )
              .append( "| a | b |\n|---|---|\n| 1 | 2 |\n\n" )
              .append( "```java\nint x = " ).append( s ).append( ";\n```\n\n" );
            s++;
        }
        return sb.toString();
    }

    static String linksTo( final String prefix, final int count ) {
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < count; i++ ) {
            sb.append( "See [[" ).append( prefix ).append( i ).append( "]]. " );
        }
        return sb.toString();
    }

    static String scanPage( final int i, final int n ) {
        final StringBuilder sb = new StringBuilder( "---\ntitle: Scan Title " + i + "\n---\n# Scan " + i + "\n\n" );
        if ( i % 10 == 5 ) {
            sb.append( "Hub link [[ScanPage0]].\n\n" );
        }
        for ( int k = 1; k <= 10; k++ ) {
            final int t = ( i * 31 + k * 97 ) % n;
            sb.append( switch ( k % 4 ) {
                case 0 -> "[[ScanPage" + t + "]]";
                case 1 -> "[[scanpage" + t + "|x]]";
                case 2 -> "[[Scan Title " + t + "]]";
                default -> "[[ScanPage" + t + "#Scan " + t + "]]";
            } ).append( " text " ).append( "lorem ipsum dolor sit amet ".repeat( 10 ) ).append( "\n\n" );
        }
        return sb.toString();
    }

    static Map< String, String > vault( final int n ) {
        final Map< String, String > m = new LinkedHashMap<>();
        for ( int i = 0; i < n; i++ ) {
            final StringBuilder sb = new StringBuilder( "---\ntags: [imported, topic" + ( i % 13 ) + "]\naliases: [note alias " + i
                    + "]\n---\n# Note " + i + "\n\n" );
            int k = 0;
            while ( sb.length() < 5_000 ) {
                final int t = ( i * 17 + k * 101 ) % n;
                sb.append( "Paragraph " ).append( k ).append( " links to [[Note " ).append( t ).append( "]] and [[Note " )
                  .append( ( t + 3 ) % n ).append( "|alias]] with #tag" ).append( k % 5 ).append( ". " )
                  .append( "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor. ".repeat( 3 ) )
                  .append( "\n\n" );
                k++;
            }
            m.put( "folder" + ( i % 20 ) + "/Note " + i + ".md", sb.toString() );
        }
        return m;
    }

    // ---- engine helpers ----------------------------------------------------------------------------------------

    static TestEngine markdownEngine() {
        final Properties p = TestEngine.getTestProperties();
        markdown( p );
        return TestEngine.build( p );
    }

    /**
     * Binds a pgvector test database (PostgresTestDb, all migrations applied) as the JNDI datasource so the engine
     * wires the structural spine (title index), Knowledge subsystem and DB policy exactly as production does. Without
     * it the title index never exists and native-link resolution skips its case-insensitive and title steps.
     */
    static synchronized void bindDatabase() throws Exception {
        if ( dbBound ) {
            return;
        }
        // production runs behind Tomcat's JNDI connection pool; an unpooled PGSimpleDataSource would add a SCRAM
        // handshake to every getConnection() and dominate every DB-touching path
        final javax.sql.DataSource ds = new PooledDataSource( com.wikantik.jdbc.testing.PostgresTestDb.createDataSource() );
        com.wikantik.TestJNDIContext.initialize();
        final javax.naming.Context initCtx = new javax.naming.InitialContext();
        try {
            initCtx.bind( "java:comp/env", new com.wikantik.TestJNDIContext() );
        } catch ( final javax.naming.NameAlreadyBoundException e ) {
            System.err.println( "java:comp/env already bound, reusing it: " + e.getMessage() );
        }
        final javax.naming.Context ctx = ( javax.naming.Context ) initCtx.lookup( "java:comp/env" );
        ctx.bind( com.wikantik.auth.AbstractJDBCDatabase.DEFAULT_DATASOURCE, ds );
        dbBound = true;
    }

    private static boolean dbBound;

    /** Minimal connection pool: physical connections are reused; close() resets state and returns them. */
    static final class PooledDataSource implements javax.sql.DataSource {
        private final javax.sql.DataSource target;
        private final java.util.concurrent.BlockingDeque< java.sql.Connection > idle = new java.util.concurrent.LinkedBlockingDeque<>();

        PooledDataSource( final javax.sql.DataSource target ) {
            this.target = target;
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            java.sql.Connection physical = idle.pollFirst();
            if ( physical == null || physical.isClosed() ) {
                physical = target.getConnection();
            }
            final java.sql.Connection p = physical;
            final boolean[] closed = { false };
            return ( java.sql.Connection ) Proxy.newProxyInstance( java.sql.Connection.class.getClassLoader(),
                    new Class< ? >[]{ java.sql.Connection.class }, ( proxy, m, a ) -> {
                        switch ( m.getName() ) {
                            case "close" -> {
                                if ( !closed[ 0 ] ) {
                                    closed[ 0 ] = true;
                                    if ( !p.getAutoCommit() ) {
                                        p.rollback();
                                        p.setAutoCommit( true );
                                    }
                                    idle.offerFirst( p );
                                }
                                return null;
                            }
                            case "isClosed" -> {
                                return closed[ 0 ];
                            }
                            case "unwrap" -> {
                                return p.unwrap( ( Class< ? > ) a[ 0 ] );
                            }
                            default -> {
                                try {
                                    return m.invoke( p, a );
                                } catch ( final InvocationTargetException e ) {
                                    throw e.getCause();
                                }
                            }
                        }
                    } );
        }

        @Override
        public java.sql.Connection getConnection( final String u, final String pw ) throws java.sql.SQLException {
            return getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws java.sql.SQLException {
            return target.getLogWriter();
        }

        @Override
        public void setLogWriter( final java.io.PrintWriter out ) throws java.sql.SQLException {
            target.setLogWriter( out );
        }

        @Override
        public void setLoginTimeout( final int seconds ) throws java.sql.SQLException {
            target.setLoginTimeout( seconds );
        }

        @Override
        public int getLoginTimeout() throws java.sql.SQLException {
            return target.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return target.getParentLogger();
        }

        @Override
        public < T > T unwrap( final Class< T > iface ) throws java.sql.SQLException {
            return target.unwrap( iface );
        }

        @Override
        public boolean isWrapperFor( final Class< ? > iface ) throws java.sql.SQLException {
            return target.isWrapperFor( iface );
        }
    }

    static void markdown( final Properties p ) {
        if ( !Boolean.getBoolean( "perf.nodb" ) ) {
            try {
                bindDatabase();
            } catch ( final Exception e ) {
                throw new IllegalStateException( "could not bind the test database: " + e.getMessage(), e );
            }
            p.setProperty( "wikantik.datasource", com.wikantik.auth.AbstractJDBCDatabase.DEFAULT_DATASOURCE );
        }
        p.setProperty( "wikantik.renderingManager.markupParser", MarkdownParser.class.getName() );
        p.setProperty( "wikantik.renderingManager.renderer", MarkdownRenderer.class.getName() );
        p.setProperty( "wikantik.translatorReader.matchEnglishPlurals", "true" );
    }

    static void awaitIndex( final TestEngine engine ) throws InterruptedException {
        final long deadline = System.nanoTime() + 120_000_000_000L;
        while ( !WikiLinkResolver.forEngine( engine ).indexReady() ) {
            if ( System.nanoTime() > deadline ) {
                throw new IllegalStateException( "title index never became ready" );
            }
            Thread.sleep( 20 );
        }
    }

    static Context viewContext( final TestEngine engine, final Page page ) {
        final Context c = Wiki.context().create( engine, page );
        c.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return c;
    }

    static void attach( final TestEngine engine, final String owner, final String file ) throws Exception {
        final Attachment att = Wiki.contents().attachment( engine, owner, file );
        att.setAuthor( "perf" );
        engine.getManager( AttachmentManager.class ).storeAttachment( att, engine.makeAttachmentFile() );
    }

    /** Wraps the managers whose calls we count in counting proxies. */
    static void installCounters( final TestEngine engine ) {
        engine.setManager( AttachmentManager.class, counting( AttachmentManager.class, engine.getManager( AttachmentManager.class ), "att." ) );
        engine.setManager( CommandResolver.class, counting( CommandResolver.class, engine.getManager( CommandResolver.class ), "cmd." ) );
        engine.setManager( StructuralIndexService.class,
                counting( StructuralIndexService.class, engine.getManager( StructuralIndexService.class ), "spine." ) );
    }

    @SuppressWarnings( "unchecked" )
    static < T > T counting( final Class< T > type, final T delegate, final String prefix ) {
        return ( T ) Proxy.newProxyInstance( type.getClassLoader(), new Class< ? >[]{ type }, ( proxy, m, a ) -> {
            COUNTS.computeIfAbsent( prefix + m.getName(), k -> new AtomicLong() ).incrementAndGet();
            try {
                return m.invoke( delegate, a );
            } catch ( final InvocationTargetException e ) {
                throw e.getCause();
            }
        } );
    }

    // ---- measurement -------------------------------------------------------------------------------------------

    @FunctionalInterface
    interface Work {
        Object run() throws Exception;
    }

    static long timeOnce( final Work w ) throws Exception {
        final long t0 = System.nanoTime();
        w.run();
        return System.nanoTime() - t0;
    }

    /** Warm-up then timed iterations; reports median/p90/mean ms and allocated bytes per iteration (this thread). */
    static void timed( final String name, final int warmup, final int iters, final Work w ) throws Exception {
        for ( int i = 0; i < warmup; i++ ) {
            w.run();
        }
        final com.sun.management.ThreadMXBean mx = ( com.sun.management.ThreadMXBean ) ManagementFactory.getThreadMXBean();
        final long tid = Thread.currentThread().threadId();
        final long[] ns = new long[ iters ];
        final long alloc0 = mx.getThreadAllocatedBytes( tid );
        boolean selfTimed = false;
        for ( int i = 0; i < iters; i++ ) {
            final long t0 = System.nanoTime();
            final Object r = w.run();
            final long el = System.nanoTime() - t0;
            if ( r instanceof Long inner ) {
                ns[ i ] = inner; // the work timed its own critical section
                selfTimed = true;
            } else {
                ns[ i ] = el;
            }
        }
        final long alloc = ( mx.getThreadAllocatedBytes( tid ) - alloc0 ) / iters;
        Arrays.sort( ns );
        report( name, "median_ms", ms( ns[ iters / 2 ] ) );
        report( name, "p90_ms", ms( ns[ ( int ) ( iters * 0.9 ) ] ) );
        report( name, "mean_ms", ms( Arrays.stream( ns ).sum() / iters ) );
        if ( !selfTimed ) {
            report( name, "alloc_kb_per_iter", alloc / 1024.0 );
        }
    }

    static void reportCounts( final String name ) throws IOException {
        for ( final Map.Entry< String, AtomicLong > e : new java.util.TreeMap<>( COUNTS ).entrySet() ) {
            report( name, "calls." + e.getKey(), e.getValue().get() );
        }
    }

    static double ms( final long ns ) {
        return ns / 1e6;
    }

    static int count( final String s, final String needle ) {
        int c = 0;
        for ( int i = s.indexOf( needle ); i >= 0; i = s.indexOf( needle, i + 1 ) ) {
            c++;
        }
        return c;
    }

    static void report( final String name, final String metric, final double value ) throws IOException {
        final String line = String.format( Locale.ROOT, "%s\t%s\t%s\t%.3f%n", label, name, metric, value );
        System.out.print( "PERF " + line );
        Files.writeString( OUT, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND );
    }

    static {
        final String l = System.getenv( "PERF_LABEL" );
        if ( l != null && !l.isBlank() ) {
            label = l;
        }
    }
}
