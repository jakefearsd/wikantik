package com.wikantik.pagegraph.references;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the on-disk snapshot format: {@code refmgr-2.7.3.ser} was written by the 2.7.3 manager for the graph
 * Alpha to {Beta, Gamma}, Beta to {Gamma}. A manager must still warm-start from it; if it could not, every
 * upgrade would silently rescan the whole wiki at boot.
 */
class ReferenceSnapshotFixtureTest {

    private static final Date LONG_BEFORE_THE_SNAPSHOT = new Date( 0L );

    @Test
    void aManagerWarmStartsFromTheSnapshotWrittenByRelease273( @TempDir final File workDir ) throws Exception {
        try ( InputStream fixture = getClass().getResourceAsStream( "/references/refmgr-2.7.3.ser" ) ) {
            assertNotNull( fixture, "fixture refmgr-2.7.3.ser is missing" );
            Files.copy( fixture, new File( workDir, "refmgr.ser" ).toPath(), StandardCopyOption.REPLACE_EXISTING );
        }
        final PageManager pm = mock( PageManager.class );
        final DefaultReferenceManager mgr = manager( workDir, pm );

        mgr.initialize( List.of( page( "Alpha", pm ), page( "Beta", pm ), page( "Gamma", pm ) ) );

        assertEquals( Set.of( "Beta", "Gamma" ), Set.copyOf( mgr.findRefersTo( "Alpha" ) ) );
        assertEquals( Set.of( "Gamma" ), Set.copyOf( mgr.findRefersTo( "Beta" ) ) );
        assertEquals( Set.of( "Alpha", "Beta" ), mgr.findReferrers( "Gamma" ) );
        assertEquals( Set.of( "Alpha" ), mgr.findReferrers( "Beta" ) );
    }

    private static Page page( final String name, final PageManager pm ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( LONG_BEFORE_THE_SNAPSHOT );
        when( p.getAttributes() ).thenReturn( Map.of() );
        when( pm.getPage( name ) ).thenReturn( p );
        return p;
    }

    private static DefaultReferenceManager manager( final File workDir, final PageManager pm ) throws Exception {
        final AttachmentManager am = mock( AttachmentManager.class );
        when( am.listAttachments( any( Page.class ) ) ).thenReturn( Collections.emptyList() );
        when( pm.getPageText( anyString(), anyInt() ) ).thenReturn( "no links" );
        when( pm.wikiPageExists( anyString() ) ).thenReturn( true );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pm )
                .with( AttachmentManager.class, am )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        return new DefaultReferenceManager( engine, pm, am );
    }
}
