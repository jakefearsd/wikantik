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
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage for the {@code --page-pattern} glob → anchored regex translation. */
class BootstrapExtractionCliGlobTest {

    @Test
    void starMatchesAnyTrailingChars() {
        final Pattern p = BootstrapExtractionCli.globToRegex( "Foo*" );
        assertTrue( p.matcher( "FooBar" ).matches() );
        assertTrue( p.matcher( "Foo" ).matches() );
        assertFalse( p.matcher( "Bar" ).matches() );
        assertFalse( p.matcher( "BarFoo" ).matches(), "anchored at start" );
    }

    @Test
    void questionMarkMatchesExactlyOneChar() {
        final Pattern p = BootstrapExtractionCli.globToRegex( "Foo?" );
        assertTrue( p.matcher( "Foos" ).matches() );
        assertFalse( p.matcher( "Foo" ).matches(), "? requires one char" );
        assertFalse( p.matcher( "Fooss" ).matches() );
    }

    @Test
    void regexMetacharsAreEscapedAndMatchLiterally() {
        // '.' and '+' must be literal, not regex operators.
        final Pattern p = BootstrapExtractionCli.globToRegex( "a.b+c" );
        assertTrue( p.matcher( "a.b+c" ).matches() );
        assertFalse( p.matcher( "axbxc" ).matches() );
    }

    @Test
    void plainNameIsAnchoredExactMatch() {
        final Pattern p = BootstrapExtractionCli.globToRegex( "Exact" );
        assertTrue( p.matcher( "Exact" ).matches() );
        assertFalse( p.matcher( "Exactly" ).matches() );
        assertFalse( p.matcher( "PreExact" ).matches() );
    }
}
