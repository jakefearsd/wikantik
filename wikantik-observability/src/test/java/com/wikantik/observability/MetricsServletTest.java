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
package com.wikantik.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class MetricsServletTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private ServletContext servletContext;

    private StringWriter responseBody;
    private MetricsServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        responseBody = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( responseBody ) );

        servlet = new MetricsServlet() {
            @Override
            public ServletContext getServletContext() {
                return servletContext;
            }
        };
    }

    @Test
    void scrapesPrometheusMetrics() throws Exception {
        final PrometheusMeterRegistry registry = new PrometheusMeterRegistry( PrometheusConfig.DEFAULT );
        Counter.builder( "test.counter" ).register( registry ).increment( 42 );
        when( servletContext.getAttribute( MetricsServlet.REGISTRY_ATTR ) ).thenReturn( registry );

        servlet.doGet( request, response );

        verify( response ).setContentType( "text/plain; version=0.0.4; charset=utf-8" );
        final String body = responseBody.toString();
        assertTrue( body.contains( "test_counter_total" ), "Should contain Prometheus counter metric" );
        assertTrue( body.contains( "42.0" ), "Should contain counter value" );
    }

    @Test
    void returns503WhenRegistryNotAvailable() throws Exception {
        when( servletContext.getAttribute( MetricsServlet.REGISTRY_ATTR ) ).thenReturn( null );

        servlet.doGet( request, response );

        verify( response ).setStatus( 503 );
        assertTrue( responseBody.toString().contains( "not available" ) );
    }

}
