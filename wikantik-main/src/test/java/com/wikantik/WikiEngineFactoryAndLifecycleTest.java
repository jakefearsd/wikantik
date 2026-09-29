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
package com.wikantik;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.search.embedding.AsyncEmbeddingIndexListener;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.mockito.Mockito.*;

/**
 * Covers {@link WikiEngine} surfaces that the standard {@link TestEngine}-based test
 * suite never exercises: the static {@code getInstance} factory family (production
 * code always goes through servlet-container bootstrap, never through TestEngine's
 * own constructor path), several rarely-hit branches in {@code initManagers}'
 * reflective URLConstructor loading, and a handful of small package-private helpers.
 *
 * <p>Every {@code getInstance} test uses its own uniquely-named working/page/attachment
 * directories (mirroring what {@code TestEngine}'s private {@code cleanTestProps} does)
 * so a real engine boot here can never collide with another concurrently-running
 * {@code TestEngine} instance's directories.
 */
class WikiEngineFactoryAndLifecycleTest {

    /** Builds an isolated properties set for a directly-constructed (non-TestEngine) engine. */
    private static Properties isolatedProps( final String label ) {
        final Properties props = TestEngine.getTestProperties();
        final String suffix = "getinstance-" + label + "-" + System.nanoTime();
        props.setProperty( "wikantik.fileSystemProvider.pageDir", "target/" + suffix + "/pages" );
        props.setProperty( "wikantik.basicAttachmentProvider.storageDir", "target/" + suffix + "/attachments" );
        props.setProperty( "wikantik.workDir", "target/" + suffix + "/work" );
        props.put( com.wikantik.auth.AuthenticationManager.PROP_LOGIN_THROTTLING, "false" );
        return props;
    }

    // -----------------------------------------------------------------------
    // getInstance(ServletContext, Properties) / getInstance(ServletConfig[, Properties])
    // -----------------------------------------------------------------------

    @Test
    void testGetInstance_firstCall_bootsRealEngineAndStashesItOnContext() throws Exception {
        final ServletContext ctx = HttpMockFactory.createServletContext( "getinstance-happy-path" );
        final Properties props = isolatedProps( "happy" );

        final WikiEngine engine = WikiEngine.getInstance( ctx, props );
        try {
            Assertions.assertNotNull( engine );
            Assertions.assertSame( engine, ctx.getAttribute( "com.wikantik.WikiEngine" ) );

            // Second call must return the cached instance, not build a new one.
            Assertions.assertSame( engine, WikiEngine.getInstance( ctx, props ) );

            // getInstance(ServletConfig) / getInstance(ServletConfig, Properties) delegate
            // to getInstance(ServletContext, Properties) — both should resolve to the
            // already-cached engine rather than re-initializing.
            final ServletConfig conf = Mockito.mock( ServletConfig.class );
            when( conf.getServletContext() ).thenReturn( ctx );
            Assertions.assertSame( engine, WikiEngine.getInstance( conf ) );
            Assertions.assertSame( engine, WikiEngine.getInstance( conf, props ) );

            Assertions.assertDoesNotThrow( engine::getHybridIndexListener );
            Assertions.assertSame( engine.serviceRegistry(), engine.serviceRegistry() );
        } finally {
            engine.stop();
        }
    }

    @Test
    void testGetInstance_urlConstructorClassNotFound_wrapsAsInternalWikiException() {
        final ServletContext ctx = HttpMockFactory.createServletContext( "getinstance-classnotfound" );
        final Properties props = isolatedProps( "cnf" );
        props.setProperty( Engine.PROP_URLCONSTRUCTOR, "TotallyBogusUrlConstructorClassName12345" );

        final InternalWikiException ex = Assertions.assertThrows( InternalWikiException.class,
            () -> WikiEngine.getInstance( ctx, props ) );

        final Throwable rootCause = ex.getCause().getCause().getCause();
        Assertions.assertInstanceOf( ClassNotFoundException.class, rootCause );
    }

    @Test
    void testGetInstance_urlConstructorAbstractClassNoArgCtor_wrapsInstantiationException() {
        final ServletContext ctx = HttpMockFactory.createServletContext( "getinstance-instantiation" );
        final Properties props = isolatedProps( "ie" );
        // java.lang.Number is abstract with no declared constructor, so the compiler
        // generates a PUBLIC default one (matching the class's own public modifier):
        // reflective newInstance() passes the access check, then fails on abstractness
        // -> InstantiationException (verified directly against this JDK; several other
        // abstract JDK classes declare an explicit *protected* no-arg constructor instead,
        // which trips IllegalAccessException first).
        props.setProperty( Engine.PROP_URLCONSTRUCTOR, "java.lang.Number" );

        final InternalWikiException ex = Assertions.assertThrows( InternalWikiException.class,
            () -> WikiEngine.getInstance( ctx, props ) );

        final Throwable rootCause = ex.getCause().getCause().getCause();
        Assertions.assertInstanceOf( InstantiationException.class, rootCause );
    }

    @Test
    void testGetInstance_urlConstructorInaccessibleCtor_wrapsIllegalAccessException() {
        final ServletContext ctx = HttpMockFactory.createServletContext( "getinstance-illegalaccess" );
        final Properties props = isolatedProps( "iae" );
        // java.util.AbstractList's no-arg constructor is protected: reflective
        // newInstance() from a different package fails the access check first.
        props.setProperty( Engine.PROP_URLCONSTRUCTOR, "java.util.AbstractList" );

        final InternalWikiException ex = Assertions.assertThrows( InternalWikiException.class,
            () -> WikiEngine.getInstance( ctx, props ) );

        final Throwable rootCause = ex.getCause().getCause().getCause();
        Assertions.assertInstanceOf( IllegalAccessException.class, rootCause );
    }

    @Test
    void testGetInstance_servletContainerTooOld_throwsInternalWikiException() {
        // A bare mock reporting an unsupported servlet version — logStartupBannerAndValidateContainer()
        // throws before any directory/manager setup happens, so no isolated dirs are needed.
        final ServletContext ctx = Mockito.mock( ServletContext.class );
        when( ctx.getMajorVersion() ).thenReturn( 2 );
        final Properties props = isolatedProps( "oldcontainer" );

        Assertions.assertThrows( InternalWikiException.class, () -> WikiEngine.getInstance( ctx, props ) );
    }

    // -----------------------------------------------------------------------
    // Small package-private helpers, exercised directly on a real engine.
    // -----------------------------------------------------------------------

    @Test
    void testCheckWorkingDirectory_trueConditionThrows_falseConditionIsNoOp() throws Exception {
        final TestEngine engine = TestEngine.build();
        try {
            Assertions.assertDoesNotThrow( () -> engine.checkWorkingDirectory( false, "unused" ) );
            final WikiException ex = Assertions.assertThrows( WikiException.class,
                () -> engine.checkWorkingDirectory( true, "boom message" ) );
            Assertions.assertEquals( "boom message", ex.getMessage() );
        } finally {
            engine.stop();
        }
    }

    @Test
    void testInitExtraComponents_registersValidClassAndLogsFailureForInvalidOne() throws Exception {
        final TestEngine engine = TestEngine.build();
        try {
            // initExtraComponents(Map<initClass, registryTypeClass>): the KEY is the class name to
            // instantiate (ClassUtil.getMappedObject); the VALUE is resolved via Class.forName to
            // the type the instance is registered under.
            final Map< String, String > extraComponents = new LinkedHashMap<>();
            extraComponents.put( "java.lang.Object", "java.lang.Object" );
            extraComponents.put( "totally.bogus.ClassName.DoesNotExist", "java.lang.String" );

            Assertions.assertDoesNotThrow( () -> engine.initExtraComponents( extraComponents ) );
            Assertions.assertNotNull( engine.getManager( Object.class ) );
        } finally {
            engine.stop();
        }
    }

    @Test
    void testEnforceValidTemplateDirectory_missingCustomTemplate_fallsBackToDefault() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( Engine.PROP_TEMPLATEDIR, "totally-bogus-template-xyz" );

        final TestEngine engine = TestEngine.build( props );
        try {
            Assertions.assertEquals( Engine.DEFAULT_TEMPLATE_NAME, engine.getTemplateDir() );
        } finally {
            engine.stop();
        }
    }

    @Test
    void testGetAuditServiceAndReadPolicy_nullWithoutDatasource() throws Exception {
        final TestEngine engine = TestEngine.build();
        try {
            Assertions.assertNull( engine.getAuditService() );
            Assertions.assertNull( engine.getAuditReadPolicy() );
        } finally {
            engine.stop();
        }
    }

    @Test
    void testRemoveWikiEventListener_doesNotThrow() throws Exception {
        final TestEngine engine = TestEngine.build();
        try {
            final com.wikantik.event.WikiEventListener listener = event -> { };
            engine.addWikiEventListener( listener );
            Assertions.assertDoesNotThrow( () -> engine.removeWikiEventListener( listener ) );
        } finally {
            engine.stop();
        }
    }

    @Test
    void testGetAllInlinedImagePatterns_returnsNonEmptyCollection() throws Exception {
        final TestEngine engine = TestEngine.build();
        try {
            Assertions.assertFalse( engine.getAllInlinedImagePatterns().isEmpty() );
        } finally {
            engine.stop();
        }
    }

    @Test
    void testPlainFieldAccessors_setThenGetReturnsSameInstance() throws Exception {
        final TestEngine engine = TestEngine.build();
        try {
            final com.wikantik.search.hybrid.ChunkVectorIndex chunkIndex =
                mock( com.wikantik.search.hybrid.ChunkVectorIndex.class );
            engine.setChunkVectorIndex( chunkIndex );
            Assertions.assertSame( chunkIndex, engine.getChunkVectorIndex() );

            final com.wikantik.api.querylog.QueryLogService queryLogService =
                mock( com.wikantik.api.querylog.QueryLogService.class );
            engine.setQueryLogService( queryLogService );
            Assertions.assertSame( queryLogService, engine.queryLogService() );

            final com.wikantik.api.querylog.QueryLogReader queryLogReader =
                mock( com.wikantik.api.querylog.QueryLogReader.class );
            engine.setQueryLogReader( queryLogReader );
            Assertions.assertSame( queryLogReader, engine.queryLogReader() );

            final com.wikantik.insights.runtime.ContentOpportunityService opportunityService =
                mock( com.wikantik.insights.runtime.ContentOpportunityService.class );
            engine.setContentOpportunityService( opportunityService );
            Assertions.assertSame( opportunityService, engine.contentOpportunityService() );

            final com.wikantik.api.briefing.BriefingLogService briefingLogService =
                mock( com.wikantik.api.briefing.BriefingLogService.class );
            engine.setBriefingLogService( briefingLogService );
            Assertions.assertSame( briefingLogService, engine.briefingLogService() );
        } finally {
            engine.stop();
        }
    }

    @Test
    void testShutdown_closesRegisteredCloseableAndSwallowsCloseFailure() throws Exception {
        final TestEngine engine = TestEngine.build();
        final AsyncEmbeddingIndexListener failingCloseable = mock( AsyncEmbeddingIndexListener.class );
        doThrow( new RuntimeException( "close boom" ) ).when( failingCloseable ).close();
        engine.setManager( AsyncEmbeddingIndexListener.class, failingCloseable );

        // A second, distinct closeQuietly() call site that closes cleanly — exercises the
        // non-exceptional fallthrough of closeable.close() (JaCoco only marks that line
        // covered when a probe past the call site actually fires, which never happens on
        // an always-throwing mock alone).
        final com.wikantik.knowledge.extraction.AsyncEntityExtractionListener cleanCloseable =
            mock( com.wikantik.knowledge.extraction.AsyncEntityExtractionListener.class );
        engine.setManager( com.wikantik.knowledge.extraction.AsyncEntityExtractionListener.class, cleanCloseable );

        // shutdown() must swallow the close() failure and still complete.
        Assertions.assertDoesNotThrow( engine::shutdown );
        verify( failingCloseable ).close();
        verify( cleanCloseable ).close();
    }

    // -----------------------------------------------------------------------
    // parseAuditedClusters — pure static helper (package-visible for testing)
    // -----------------------------------------------------------------------

    @Test
    void testParseAuditedClusters_nullOrBlank_returnsEmptySet() {
        Assertions.assertTrue( WikiEngine.parseAuditedClusters( null ).isEmpty() );
        Assertions.assertTrue( WikiEngine.parseAuditedClusters( "" ).isEmpty() );
        Assertions.assertTrue( WikiEngine.parseAuditedClusters( "   " ).isEmpty() );
    }

    @Test
    void testParseAuditedClusters_trimsAndDropsEmptyEntries() {
        final java.util.Set< String > result = WikiEngine.parseAuditedClusters( " engineering , , finance ,engineering" );
        Assertions.assertEquals( java.util.Set.of( "engineering", "finance" ), result );
    }
}
