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
package com.wikantik.markdown.extensions.fences;

import com.wikantik.markdown.extensions.fences.PlantUmlCheckReport.Diagram;
import com.wikantik.plantuml.RenderOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The unexpected-failure and rarely-taken branches of the PlantUML renderer, drawer and corpus checker. */
class PlantUmlFailurePathsTest {

    @TempDir
    Path dir;

    private static InputStreamHolder stdin( final String s ) {
        return new InputStreamHolder( new ByteArrayInputStream( s.getBytes( StandardCharsets.UTF_8 ) ) );
    }

    private record InputStreamHolder( ByteArrayInputStream in ) {
    }

    // ---- PlantUmlFenceRenderer -----------------------------------------------------------------------------------

    @Test
    void rendererRefusesAsRenderFailedWhenThePolicyThrows() {
        final PlantUmlFenceRenderer r = new PlantUmlFenceRenderer( s -> {
            throw new IllegalStateException( "policy exploded" );
        } );
        final FenceVerdict v = r.check( "A -> B\n" );
        assertFalse( v.allowed() );
        assertEquals( "render-failed", v.reason() );
    }

    @Test
    void rendererReportsNoLinksWhenThePolicyThrows() {
        final PlantUmlFenceRenderer r = new PlantUmlFenceRenderer( s -> {
            throw new IllegalStateException( "policy exploded" );
        } );
        assertEquals( java.util.Set.of(), r.pageLinks( "A -> B : [[/wiki/PageOne]]\n" ) );
    }

    // ---- PlantUmlFenceDrawer -------------------------------------------------------------------------------------

    @Test
    void unconfirmedSandboxMakesEveryFenceRenderFailed() {
        final AtomicInteger services = new AtomicInteger();
        final PlantUmlFenceDrawer d = PlantUmlFenceDrawer.fromProperties( new Properties(), () -> false, s -> {
            services.incrementAndGet();
            return null;
        } );
        final ServerDrawing out = d.draw( "A -> B\n", 1_000L );
        assertFalse( out.isDrawn() );
        assertEquals( "render-failed", out.reason() );
        assertTrue( out.transientFailure() );
        assertEquals( 0, services.get(), "no service is built when the sandbox cannot be confirmed" );
    }

    @Test
    void serviceConstructionFailureMakesEveryFenceRenderFailed() {
        final PlantUmlFenceDrawer d = PlantUmlFenceDrawer.fromProperties( new Properties(), () -> true, s -> {
            throw new IllegalStateException( "no pool" );
        } );
        assertEquals( "render-failed", d.draw( "A -> B\n", 1_000L ).reason() );
    }

    @Test
    void linkageErrorDuringSetupIsAlsoContained() {
        final PlantUmlFenceDrawer d = PlantUmlFenceDrawer.fromProperties( new Properties(), () -> true, s -> {
            throw new NoClassDefFoundError( "net/sourceforge/plantuml/Missing" );
        } );
        final ServerDrawing out = d.draw( "A -> B\n", 1_000L );
        assertEquals( "render-failed", out.reason() );
        assertTrue( out.transientFailure() );
    }

    // ---- PlantUmlDiagramChecker ----------------------------------------------------------------------------------

    private List< Diagram > check( final String md, final Function< String, RenderOutcome > renderer ) throws IOException {
        return PlantUmlDiagramChecker.checkAll( List.of( "-" ), stdin( md ).in(), () -> renderer );
    }

    @Test
    void transientRefusalIsRetriedOnce() throws IOException {
        final AtomicInteger calls = new AtomicInteger();
        final List< Diagram > out = check( "```plantuml\nA -> B\n```\n", s -> {
            return calls.incrementAndGet() == 1 ? RenderOutcome.transientRefusal( "busy" )
                    : RenderOutcome.refused( "syntax-error", "bad", 0 );
        } );
        assertEquals( 2, calls.get() );
        assertEquals( "syntax-error: bad", out.get( 0 ).error() );
    }

    @Test
    void aPersistentlyTransientRefusalIsReportedAfterTheRetry() throws IOException {
        final AtomicInteger calls = new AtomicInteger();
        final List< Diagram > out = check( "```plantuml\nA -> B\n```\n", s -> {
            calls.incrementAndGet();
            return RenderOutcome.transientRefusal( "busy" );
        } );
        assertEquals( 2, calls.get(), "retried exactly once" );
        assertEquals( "busy", out.get( 0 ).error() );
    }

    @Test
    void nullReasonIsReportedAsRenderFailed() throws IOException {
        final List< Diagram > out = check( "```plantuml\nA -> B\n```\n", s -> RenderOutcome.refused( null, null, 0 ) );
        assertEquals( "render-failed", out.get( 0 ).error() );
    }

    @Test
    void errorTextCarriesTheFileRelativeLineAndTheFirstNonBlankMessageLine() throws IOException {
        // fence opens on line 3; the reported line is fence line + the renderer's source-relative line
        final List< Diagram > out = check( "x\n\n```plantuml\nA -> B\n```\n",
                s -> RenderOutcome.refused( "syntax-error", "\n  \n  Syntax Error?  \nsecond line", 2 ) );
        assertEquals( "syntax-error line 5: Syntax Error?", out.get( 0 ).error() );
    }

    @Test
    void blankMessageAddsNothing() throws IOException {
        final List< Diagram > out = check( "```plantuml\nA -> B\n```\n", s -> RenderOutcome.refused( "busy", " \n ", 0 ) );
        assertEquals( "busy", out.get( 0 ).error() );
    }

    @Test
    void theRendererIsBuiltLazilyAndNotAtAllWithoutDiagrams() throws IOException {
        final AtomicInteger builds = new AtomicInteger();
        final List< Diagram > none = PlantUmlDiagramChecker.checkAll( List.of( "-" ),
                stdin( "# nothing\n\n```java\nint x;\n```\n\n```plantuml example\nA -> B\n```\n" ).in(), () -> {
                    builds.incrementAndGet();
                    return s -> null;
                } );
        assertTrue( none.isEmpty() );
        assertEquals( 0, builds.get() );

        final AtomicInteger b2 = new AtomicInteger();
        PlantUmlDiagramChecker.checkAll( List.of( "-" ), stdin( "```plantuml\nA\n```\n\n```plantuml\nB\n```\n" ).in(), () -> {
            b2.incrementAndGet();
            return s -> RenderOutcome.refused( "x", null, 0 );
        } );
        assertEquals( 1, b2.get(), "one renderer serves every diagram" );
    }

    @Test
    void directoryWalkTakesOnlyMarkdownFilesInPathOrder() throws IOException {
        Files.createDirectories( dir.resolve( "sub/deeper" ) );
        final String fence = "```plantuml\nA -> B\n```\n";
        Files.writeString( dir.resolve( "b.md" ), fence );
        Files.writeString( dir.resolve( "a.md" ), fence );
        Files.writeString( dir.resolve( "notes.txt" ), fence );
        Files.writeString( dir.resolve( "README" ), fence );
        Files.writeString( dir.resolve( "sub/deeper/c.md" ), fence );
        Files.writeString( dir.resolve( "sub/d.markdown" ), fence );

        final List< Diagram > out = PlantUmlDiagramChecker.checkAll( List.of( dir.toString() ), stdin( "" ).in(),
                () -> s -> RenderOutcome.refused( "x", null, 0 ) );

        final List< String > files = new ArrayList<>();
        out.forEach( d -> files.add( Path.of( d.file() ).getFileName().toString() ) );
        assertEquals( List.of( "a.md", "b.md", "c.md" ), files );
    }

    @Test
    void aSingleFileTargetIsReadWhateverItsExtension() throws IOException {
        final Path f = dir.resolve( "page.txt" );
        Files.writeString( f, "```plantuml\nA -> B\n```\n" );
        final List< Diagram > out = PlantUmlDiagramChecker.checkAll( List.of( f.toString() ), stdin( "" ).in(),
                () -> s -> RenderOutcome.refused( "x", null, 0 ) );
        assertEquals( 1, out.size() );
        assertEquals( f.toString(), out.get( 0 ).file() );
    }

    @Test
    void anUnreadableFileSurfacesAsAnIoException() throws IOException {
        final Path f = dir.resolve( "bad.md" );
        Files.write( f, new byte[] { ( byte ) 0xff, ( byte ) 0xfe, ( byte ) 0xfd } );
        assertThrows( IOException.class, () -> PlantUmlDiagramChecker.checkAll( List.of( f.toString() ), stdin( "" ).in() ) );
    }

    // ---- PlantUmlCheckCli ----------------------------------------------------------------------------------------

    @Test
    void cliReportsAnUnreadableFileAsExit2InProcess() throws IOException {
        final Path f = dir.resolve( "bad.md" );
        Files.write( f, new byte[] { ( byte ) 0xff, ( byte ) 0xfe, ( byte ) 0xfd } );
        final java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        final java.io.ByteArrayOutputStream e = new java.io.ByteArrayOutputStream();

        final int code = PlantUmlCheckCli.run( new String[] { f.toString() }, new ByteArrayInputStream( new byte[ 0 ] ),
                new java.io.PrintStream( o, true, StandardCharsets.UTF_8 ), new java.io.PrintStream( e, true, StandardCharsets.UTF_8 ) );

        assertEquals( 2, code );
        assertEquals( "", o.toString( StandardCharsets.UTF_8 ), "stdout carries only the report" );
        assertTrue( e.toString( StandardCharsets.UTF_8 ).contains( "check-plantuml:" ), e.toString( StandardCharsets.UTF_8 ) );
    }

    @Test
    void cliRestoresSystemOutAfterEveryRun() {
        final java.io.PrintStream before = System.out;
        PlantUmlCheckCli.run( new String[] {}, new ByteArrayInputStream( new byte[ 0 ] ),
                new java.io.PrintStream( new java.io.ByteArrayOutputStream() ), new java.io.PrintStream( new java.io.ByteArrayOutputStream() ) );
        assertTrue( before == System.out );
    }

    @Test
    void cliIsNotInstantiable() throws ReflectiveOperationException {
        final Constructor< PlantUmlCheckCli > c = PlantUmlCheckCli.class.getDeclaredConstructor();
        c.setAccessible( true );
        assertEquals( PlantUmlCheckCli.class, c.newInstance().getClass() );
    }

    @Test
    void placeholderVerifyRejectsNullArguments() {
        final String token = FencePlaceholders.sign( "A -> B\n" );
        assertFalse( FencePlaceholders.verify( null, "A -> B\n" ) );
        assertFalse( FencePlaceholders.verify( token, null ) );
        assertTrue( FencePlaceholders.verify( token, "A -> B\n" ) );
        assertFalse( FencePlaceholders.verify( token, "A -> B" ) );
        assertFalse( FencePlaceholders.verify( token.substring( 0, token.length() - 1 ), "A -> B\n" ) );
        assertFalse( FencePlaceholders.verify( token + "0", "A -> B\n" ) );
        assertFalse( FencePlaceholders.verify( "", "A -> B\n" ) );
    }
}
