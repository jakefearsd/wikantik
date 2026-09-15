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
package com.wikantik.api.core;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link Engine#findConfigFile(String)}, the interface's one nontrivial default
 * method. A third-party {@link Engine} implementation that never overrides it must still
 * get correct WEB-INF / classpath / servlet-container resolution behaviour.
 */
class EngineTest {

    private Engine mockEngine() {
        // CALLS_REAL_METHODS lets the interface's default method actually execute.
        return Mockito.mock( Engine.class, Mockito.CALLS_REAL_METHODS );
    }

    @Test
    void findConfigFileReturnsRootPathFileWhenItExists( @TempDir final Path tempDir ) throws IOException {
        final Path webInf = tempDir.resolve( "WEB-INF" );
        Files.createDirectories( webInf );
        final Path configFile = webInf.resolve( "wikantik.policy" );
        Files.writeString( configFile, "policy-content", StandardCharsets.UTF_8 );

        final Engine engine = mockEngine();
        when( engine.getRootPath() ).thenReturn( tempDir.toAbsolutePath().toString() );

        final URL result = engine.findConfigFile( "wikantik.policy" );

        assertNotNull( result );
        assertEquals( configFile.toUri().toURL(), result );
        // The WEB-INF file wins outright — no need for getServletContext() to even be consulted.
        Mockito.verify( engine, Mockito.never() ).getServletContext();
    }

    @Test
    void findConfigFileReturnsNullWhenRootPathAbsentAndNoServletContext() {
        final Engine engine = mockEngine();
        when( engine.getRootPath() ).thenReturn( null );
        when( engine.getServletContext() ).thenReturn( null );

        assertNull( engine.findConfigFile( "nonexistent.policy" ) );
    }

    @Test
    void findConfigFileReturnsNullWhenRootPathFileMissingAndNoServletContext( @TempDir final Path tempDir ) {
        final Engine engine = mockEngine();
        // rootPath set, but no such WEB-INF file exists there.
        when( engine.getRootPath() ).thenReturn( tempDir.toAbsolutePath().toString() );
        when( engine.getServletContext() ).thenReturn( null );

        assertNull( engine.findConfigFile( "does-not-exist.policy" ) );
    }

    @Test
    void findConfigFileReturnsServletContextResourceWhenPresent() throws MalformedURLException {
        final Engine engine = mockEngine();
        when( engine.getRootPath() ).thenReturn( null );
        final ServletContext ctx = mock( ServletContext.class );
        when( engine.getServletContext() ).thenReturn( ctx );
        final URL webInfUrl = new URL( "file:/fake/WEB-INF/dummy-config-file.txt" );
        // getServletContext().getResource(...) short-circuits before the copy-to-tempfile path,
        // as long as the classpath resource (dummy-config-file.txt, on this module's test
        // classpath) is found first so the "is == null" FileNotFoundException branch isn't hit.
        when( ctx.getResource( "/WEB-INF/dummy-config-file.txt" ) ).thenReturn( webInfUrl );

        final URL result = engine.findConfigFile( "dummy-config-file.txt" );

        assertEquals( webInfUrl, result );
    }

    @Test
    void findConfigFileCopiesClasspathResourceToTempFileWhenServletContextResourceAbsent()
            throws IOException, URISyntaxException {
        final Engine engine = mockEngine();
        when( engine.getRootPath() ).thenReturn( null );
        final ServletContext ctx = mock( ServletContext.class );
        when( engine.getServletContext() ).thenReturn( ctx );
        when( ctx.getResource( "/WEB-INF/dummy-config-file.txt" ) ).thenReturn( null );

        final URL result = engine.findConfigFile( "dummy-config-file.txt" );

        assertNotNull( result, "classpath resource dummy-config-file.txt exists, so a temp-file URL must be returned" );
        final String content = Files.readString( Path.of( result.toURI() ), StandardCharsets.UTF_8 );
        assertEquals( "dummy-config-fixture-content\n", content );
    }

    @Test
    void findConfigFileReturnsNullWhenClasspathResourceMissingAndServletContextResourceAbsent() throws MalformedURLException {
        final Engine engine = mockEngine();
        when( engine.getRootPath() ).thenReturn( null );
        final ServletContext ctx = mock( ServletContext.class );
        when( engine.getServletContext() ).thenReturn( ctx );
        when( ctx.getResource( org.mockito.ArgumentMatchers.anyString() ) ).thenReturn( null );

        // No classpath resource named this way, and no WEB-INF match either —
        // the FileNotFoundException is caught internally and logged, not thrown.
        final URL result = engine.findConfigFile( "totally-nonexistent-resource.xyz" );

        assertNull( result );
    }
}
