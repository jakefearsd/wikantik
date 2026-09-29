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
package com.wikantik.attachment;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.RedirectException;
import com.wikantik.api.managers.AttachmentManager;
import jakarta.servlet.ServletConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link AttachmentServlet#init(ServletConfig)} against a real {@link TestEngine},
 * since the servlet's default extension/size configuration is otherwise only reachable via
 * container-driven initialization. Separated from {@link AttachmentServletTest} (which uses a
 * fully mocked {@code Engine}) because each scenario here needs its own {@code TestEngine} with
 * different {@code wikantik.attachment.*} properties.
 */
class AttachmentServletInitTest {

    private TestEngine testEngine;

    @AfterEach
    void tearDown() throws Exception {
        if( testEngine != null ) {
            testEngine.stop();
        }
    }

    private ServletConfig configFor( final TestEngine engine ) {
        final ServletConfig config = mock( ServletConfig.class );
        when( config.getServletContext() ).thenReturn( engine.getServletContext() );
        return config;
    }

    @Test
    void testInitWithDefaultPropertiesCreatesTmpDirAndParsesEmptyExtensionLists() throws Exception {
        // Default TestEngine properties declare neither wikantik.attachment.allowed nor
        // .forbidden, so init() must take the "else" branch for both (empty String[] patterns)
        // and successfully create the attach-tmp working directory.
        testEngine = new TestEngine( TestEngine.getTestProperties() );

        final AttachmentServlet servlet = new AttachmentServlet();
        servlet.init( configFor( testEngine ) );

        final File tmpDir = new File( testEngine.getWorkDir() + File.separator + "attach-tmp" );
        org.junit.jupiter.api.Assertions.assertTrue( tmpDir.isDirectory(), "attach-tmp directory should have been created" );
    }

    @Test
    void testInitParsesAllowedAndForbiddenExtensionProperties() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( AttachmentManager.PROP_ALLOWEDEXTENSIONS, ".txt" );
        props.setProperty( AttachmentManager.PROP_FORBIDDENEXTENSIONS, ".exe" );
        testEngine = new TestEngine( props );

        final AttachmentServlet servlet = new AttachmentServlet();
        servlet.init( configFor( testEngine ) );

        final Context context = mock( Context.class );
        when( context.hasAdminPermissions() ).thenReturn( false );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "TestPage/virus.exe" );
        when( context.getPage() ).thenReturn( page );

        final InputStream data = new java.io.ByteArrayInputStream( "x".getBytes() );

        // Forbidden pattern always wins, even though it's not on the allowed list either.
        final RedirectException ex = assertThrows( RedirectException.class,
                () -> servlet.executeUpload( context, data, "virus.exe", "http://localhost/error",
                        "TestPage", null, 1L ) );
        org.junit.jupiter.api.Assertions.assertTrue( ex.getMessage().contains( "type" ),
                "Expected a file-type rejection, got: " + ex.getMessage() );

        // A name that matches neither the (non-empty) allowed list nor the forbidden list is
        // also rejected, since allowedPatterns.length != 0 makes the allow-list exhaustive.
        final Page page2 = mock( Page.class );
        when( page2.getName() ).thenReturn( "TestPage/image.png" );
        when( context.getPage() ).thenReturn( page2 );
        final InputStream data2 = new java.io.ByteArrayInputStream( "x".getBytes() );
        assertThrows( RedirectException.class,
                () -> servlet.executeUpload( context, data2, "image.png", "http://localhost/error",
                        "TestPage", null, 1L ) );
    }

    @Test
    void testInitLogsFatalWhenTmpDirPathIsARegularFile() throws Exception {
        testEngine = new TestEngine( TestEngine.getTestProperties() );

        // Pre-create a plain file where AttachmentServlet.init() wants to create attach-tmp/.
        final File tmpDirPath = new File( testEngine.getWorkDir() + File.separator + "attach-tmp" );
        try( FileWriter fw = new FileWriter( tmpDirPath ) ) {
            fw.write( "not a directory" );
        }

        final AttachmentServlet servlet = new AttachmentServlet();

        // init() must not throw even though the temp dir path is unusable -- it only logs LOG.fatal().
        assertDoesNotThrow( () -> servlet.init( configFor( testEngine ) ) );
        org.junit.jupiter.api.Assertions.assertTrue( tmpDirPath.isFile(), "the pre-existing file must be left untouched" );
    }
}
