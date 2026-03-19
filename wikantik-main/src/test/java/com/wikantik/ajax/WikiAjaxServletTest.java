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
package com.wikantik.ajax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * @since 2.10.2-svn10
 */
public class WikiAjaxServletTest {

    @Test
    public void testServlets() throws Exception {
        final String[] paths = new String[] {
                "/ajax/MyPlugin",
                "/ajax/MyPlugin/",
                "/ajax/MyPlugin/Friend",
                "/ajax/MyPlugin?",
                "/ajax/MyPlugin?param=123&param=231",
                "/ajax/MyPlugin#hashCode?param=123&param=231",
                "http://google.com.au/test/ajax/MyPlugin#hashCode?param=123&param=231",
                "/test//ajax/MyPlugin#hashCode?param=123&param=231",
                "http://localhost:8080/ajax/MyPlugin#hashCode?param=123&param=231" };

        Assertions.assertEquals(9,paths.length);
        final WikiAjaxDispatcherServlet wikiAjaxDispatcherServlet = new WikiAjaxDispatcherServlet();
        for (final String path : paths) {
            final String servletName = wikiAjaxDispatcherServlet.getServletName(path);
            Assertions.assertEquals("MyPlugin", servletName);
        }

        // Register an inline WikiAjaxServlet implementation
        final WikiAjaxServlet testServlet = new WikiAjaxServlet() {
            @Override public String getServletMapping() { return "TestAjaxServlet"; }
            @Override public void service( final HttpServletRequest request, final HttpServletResponse response,
                                           final String actionName, final List< String > params ) { }
        };
        WikiAjaxDispatcherServlet.registerServlet( testServlet );
        final WikiAjaxServlet servlet = wikiAjaxDispatcherServlet.findServletByName("TestAjaxServlet");
        Assertions.assertNotNull(servlet);

        final WikiAjaxServlet servlet3 = wikiAjaxDispatcherServlet.findServletByName("TestWikiNonAjaxServlet");
        Assertions.assertNull(servlet3);
    }

}
