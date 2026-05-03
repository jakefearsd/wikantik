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
package com.wikantik.bootstrap;

import com.wikantik.WikiSession;
import com.wikantik.api.core.Engine;
import com.wikantik.api.eval.RetrievalQualityRunner;
import com.wikantik.knowledge.eval.DefaultRetrievalQualityRunner;
import com.wikantik.knowledge.judge.JudgeRunner;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

/**
 * TDD unit tests for {@link WikiBootstrapServletContextListener#contextDestroyed}.
 *
 * <p>Asserts that the listener's shutdown path:
 * <ul>
 *   <li>calls {@code engine.shutdown()} when an engine is present</li>
 *   <li>closes the {@link JudgeRunner} (if registered)</li>
 *   <li>closes the {@link DefaultRetrievalQualityRunner} (if registered)</li>
 *   <li>clears the WikiSession ThreadLocal for the context thread</li>
 *   <li>does not throw when no engine is present</li>
 * </ul>
 */
class WikiBootstrapServletContextListenerShutdownTest {

    private WikiBootstrapServletContextListener listener;
    private ServletContextEvent sce;
    private ServletContext ctx;
    private Engine engine;

    @BeforeEach
    void setUp() {
        listener = new WikiBootstrapServletContextListener();
        sce = mock( ServletContextEvent.class );
        ctx = mock( ServletContext.class );
        engine = mock( Engine.class );
        when( sce.getServletContext() ).thenReturn( ctx );
    }

    @Test
    void contextDestroyed_callsEngineShutdownWhenEnginePresent() {
        when( ctx.getAttribute( "com.wikantik.WikiEngine" ) ).thenReturn( engine );

        try ( final MockedStatic< WikiSession > ws = mockStatic( WikiSession.class ) ) {
            listener.contextDestroyed( sce );
        }

        verify( engine ).shutdown();
    }

    @Test
    void contextDestroyed_closesJudgeRunnerWhenRegistered() {
        when( ctx.getAttribute( "com.wikantik.WikiEngine" ) ).thenReturn( engine );
        final JudgeRunner judgeRunner = mock( JudgeRunner.class );
        when( engine.getManager( JudgeRunner.class ) ).thenReturn( judgeRunner );

        try ( final MockedStatic< WikiSession > ws = mockStatic( WikiSession.class ) ) {
            listener.contextDestroyed( sce );
        }

        verify( judgeRunner ).close();
    }

    @Test
    void contextDestroyed_closesRetrievalQualityRunnerWhenRegistered() {
        when( ctx.getAttribute( "com.wikantik.WikiEngine" ) ).thenReturn( engine );
        final DefaultRetrievalQualityRunner rqRunner = mock( DefaultRetrievalQualityRunner.class );
        when( engine.getManager( RetrievalQualityRunner.class ) )
            .thenReturn( rqRunner );

        try ( final MockedStatic< WikiSession > ws = mockStatic( WikiSession.class ) ) {
            listener.contextDestroyed( sce );
        }

        verify( rqRunner ).close();
    }

    @Test
    void contextDestroyed_removesWikiSessionThreadLocal() {
        when( ctx.getAttribute( "com.wikantik.WikiEngine" ) ).thenReturn( engine );

        try ( final MockedStatic< WikiSession > ws = mockStatic( WikiSession.class ) ) {
            listener.contextDestroyed( sce );
            ws.verify( WikiSession::removeCurrentGuestSession );
        }
    }

    @Test
    void contextDestroyed_doesNotThrowWhenNoEnginePresent() {
        when( ctx.getAttribute( "com.wikantik.WikiEngine" ) ).thenReturn( null );

        // Should not throw
        try ( final MockedStatic< WikiSession > ws = mockStatic( WikiSession.class ) ) {
            listener.contextDestroyed( sce );
        }
    }

    @Test
    void contextDestroyed_doesNotThrowWhenContextIsNull() {
        when( sce.getServletContext() ).thenReturn( null );

        // Should not throw
        try ( final MockedStatic< WikiSession > ws = mockStatic( WikiSession.class ) ) {
            listener.contextDestroyed( sce );
        }
    }

    @Test
    void contextDestroyed_closesJudgeRunnerEvenIfShutdownThrows() {
        when( ctx.getAttribute( "com.wikantik.WikiEngine" ) ).thenReturn( engine );
        doThrow( new RuntimeException( "shutdown failed" ) ).when( engine ).shutdown();
        final JudgeRunner judgeRunner = mock( JudgeRunner.class );
        when( engine.getManager( JudgeRunner.class ) ).thenReturn( judgeRunner );

        try ( final MockedStatic< WikiSession > ws = mockStatic( WikiSession.class ) ) {
            // Should not propagate exceptions
            listener.contextDestroyed( sce );
        }

        verify( judgeRunner ).close();
    }

    /** Smoke test that the static utility doesn't throw in an empty environment. */
    @Test
    void interruptAndJoinKnownBackgroundThreads_doesNotThrow() {
        WikiBootstrapServletContextListener.interruptAndJoinKnownBackgroundThreads();
    }
}
