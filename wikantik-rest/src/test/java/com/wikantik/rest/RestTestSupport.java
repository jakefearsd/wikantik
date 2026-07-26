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
package com.wikantik.rest;

import com.wikantik.TestEngine;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;

import org.mockito.Mockito;

import java.util.function.Supplier;

/**
 * Shared scaffolding for wikantik-rest servlet tests. Replaces the per-class copy of the
 * {@code Properties → new TestEngine → mock ServletConfig → servlet.init()} dance.
 *
 * <p>Intended use is the <b>per-class engine</b> lifecycle (one full engine boot per test
 * class instead of one per test method — the engine boot is ~250-400&nbsp;ms, which dominated
 * this module's runtime):</p>
 *
 * <pre>{@code
 * static TestEngine engine;
 * static PageResource servlet;
 *
 * @BeforeAll
 * static void startEngine() throws Exception {
 *     engine = TestEngine.build();
 *     servlet = RestTestSupport.initServlet( PageResource::new, engine );
 * }
 *
 * @AfterAll
 * static void stopEngine() {
 *     engine.stop();
 * }
 * }</pre>
 *
 * <p><b>Per-class engine audit checklist</b> — before migrating a class from a
 * {@code @BeforeEach} engine, confirm each point or keep the per-method lifecycle:</p>
 * <ol>
 *   <li>Fixture pages/users use per-test-unique names, or are removed in
 *       {@code @AfterEach} via {@link TestEngine#deleteQuietly(String...)}.</li>
 *   <li>No test mutates engine-global state ({@code setManager}, plugin enablement,
 *       system properties) without restoring it.</li>
 *   <li>Tests that must be anonymous build their request with
 *       {@code HttpMockFactory.createIsolatedHttpRequest(...)} — with a shared engine, any
 *       earlier {@code saveText()}/{@code adminSession()} authenticates the shared
 *       {@code HttpMockFactory.SHARED_SESSION_ID} for the rest of the class.</li>
 *   <li>No test asserts absolute engine-wide counts (page totals, session counts) that
 *       earlier tests in the class could shift.</li>
 * </ol>
 */
public final class RestTestSupport {

    private RestTestSupport() {
    }

    /**
     * Constructs the servlet and runs {@code init()} against the engine's ServletContext —
     * the boilerplate previously copy-pasted into every resource test's setUp.
     */
    public static < T extends HttpServlet > T initServlet( final Supplier< T > constructor, final TestEngine engine )
            throws ServletException {
        final T servlet = constructor.get();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
        return servlet;
    }
}
