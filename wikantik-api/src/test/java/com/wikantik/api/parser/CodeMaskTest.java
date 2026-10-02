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
package com.wikantik.api.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeMaskTest {

    @Test
    void fencedMarksOnlyFenceLines() {
        final String md = "a `x`\n```\nb\n```\n\n    indented\n";
        final boolean[] all = CodeMask.of( md );
        final boolean[] fenced = CodeMask.fenced( md );
        assertTrue( all[ md.indexOf( "x" ) ] );
        assertFalse( fenced[ md.indexOf( "x" ) ] );
        assertTrue( fenced[ md.indexOf( "b" ) ] );
        assertTrue( fenced[ md.indexOf( "```" ) ] );
        assertTrue( all[ md.indexOf( "indented" ) ] );
        assertFalse( fenced[ md.indexOf( "indented" ) ] );
        assertFalse( fenced[ 0 ] );
    }
}
