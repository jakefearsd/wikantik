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
package com.wikantik.its.environment;

import org.openqa.selenium.Keys;

import java.util.Locale;

/**
 * Platform-aware Selenium key chords for driving text editors from Selenide ITs.
 *
 * <p>Every "select all" chord in this test tree MUST go through
 * {@link #selectAll()} rather than hard-coding {@code Keys.chord( Keys.CONTROL, "a" )}.
 * The page editor's CodeMirror 6 instance binds select-all to the platform-neutral
 * key {@code Mod-a}: CodeMirror (and every other Mod-aware editor shortcut) resolves
 * {@code Mod} to Command (&#8984;) on macOS and to Control everywhere else. A test
 * that always sends {@code Keys.CONTROL} therefore works on Linux and Windows CI
 * runners but silently no-ops on a developer's Mac: Ctrl+A selects nothing, the
 * DELETE that follows removes only the character next to the caret, the buffer is
 * left non-empty, and the new text either appends to or interleaves with the old
 * content instead of replacing it. That failure surfaces far from its cause — as an
 * exact-text assertion mismatch on rendered output, sometimes several method calls
 * later.
 *
 * <p>Do not "simplify" this back to a bare {@code Keys.CONTROL} chord — that is
 * exactly the regression this class exists to prevent.
 */
public final class PlatformKeys {

    /**
     * Resolved once at class-init, not per call: {@code os.name} does not change
     * during a test run, and re-deriving it on every keystroke would just be noise.
     */
    private static final boolean IS_MACOS =
        System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT ).contains( "mac" );

    private PlatformKeys() {
    }

    /**
     * The platform-correct "select all" chord: {@code Cmd+A} on macOS,
     * {@code Ctrl+A} everywhere else.
     *
     * <p>See the class Javadoc for why this must not be replaced with a
     * hard-coded {@code Keys.CONTROL} chord.
     *
     * @return the key chord to send to select all content in the focused element.
     */
    public static CharSequence selectAll() {
        return IS_MACOS ? Keys.chord( Keys.COMMAND, "a" ) : Keys.chord( Keys.CONTROL, "a" );
    }

}
