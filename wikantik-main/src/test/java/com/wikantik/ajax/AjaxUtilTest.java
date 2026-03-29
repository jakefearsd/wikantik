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

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AjaxUtil}.
 */
class AjaxUtilTest {

    // ---- toJson ----

    @Test
    void testToJsonConvertsStringToJsonString() {
        final String result = AjaxUtil.toJson( "hello" );
        assertEquals( "\"hello\"", result );
    }

    @Test
    void testToJsonConvertsListToJsonArray() {
        final String result = AjaxUtil.toJson( List.of( "a", "b", "c" ) );
        assertEquals( "[\"a\",\"b\",\"c\"]", result );
    }

    @Test
    void testToJsonConvertsIntegerToJsonNumber() {
        final String result = AjaxUtil.toJson( 42 );
        assertEquals( "42", result );
    }

    @Test
    void testToJsonReturnsEmptyStringForNull() {
        final String result = AjaxUtil.toJson( null );
        assertEquals( "", result );
    }

    // ---- getNextPathPart ----

    @Test
    void testGetNextPathPartReturnsNullForNullPath() throws ServletException {
        assertNull( AjaxUtil.getNextPathPart( null, "/ajax/" ) );
    }

    @Test
    void testGetNextPathPartReturnsNullForBlankPath() throws ServletException {
        assertNull( AjaxUtil.getNextPathPart( "   ", "/ajax/" ) );
    }

    @Test
    void testGetNextPathPartExtractsFragmentAfterLastPart() throws ServletException {
        assertEquals( "MyPlugin", AjaxUtil.getNextPathPart( "/ajax/MyPlugin", "/ajax/" ) );
    }

    @Test
    void testGetNextPathPartStopsAtSlash() throws ServletException {
        // "/ajax/MyPlugin/action" — after "/ajax/" comes "MyPlugin", then "/action"
        assertEquals( "MyPlugin", AjaxUtil.getNextPathPart( "/ajax/MyPlugin/action", "/ajax/" ) );
    }

    @Test
    void testGetNextPathPartStopsAtHashFragment() throws ServletException {
        assertEquals( "MyPlugin", AjaxUtil.getNextPathPart( "/ajax/MyPlugin#hash", "/ajax/" ) );
    }

    @Test
    void testGetNextPathPartStopsAtQueryString() throws ServletException {
        assertEquals( "MyPlugin", AjaxUtil.getNextPathPart( "/ajax/MyPlugin?q=1", "/ajax/" ) );
    }

    @Test
    void testGetNextPathPartWorksWithLastPartWithoutTrailingSlash() throws ServletException {
        // lastPart without trailing slash — the method appends one internally
        assertEquals( "MyPlugin", AjaxUtil.getNextPathPart( "/ajax/MyPlugin", "/ajax" ) );
    }

    @Test
    void testGetNextPathPartReturnsLastPartWhenPathEndsWithIt() throws ServletException {
        // Special case: path.endsWith(lastPart) -> returns lastPart itself
        assertEquals( "/ajax/", AjaxUtil.getNextPathPart( "/context/ajax/", "/ajax/" ) );
    }

    @Test
    void testGetNextPathPartThrowsServletExceptionForInvalidPath() {
        assertThrows( ServletException.class,
                () -> AjaxUtil.getNextPathPart( "/no-match/here", "/ajax/" ) );
    }

    @Test
    void testGetNextPathPartWorksWithFullUrl() throws ServletException {
        assertEquals( "MyPlugin",
                AjaxUtil.getNextPathPart( "http://localhost:8080/ajax/MyPlugin", "/ajax/" ) );
    }

}
