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
package com.wikantik.its;

/**
 * The page editor's modes, as the app names them ({@code localStorage['wikantik.editor.mode']} and the
 * {@code editorMode} of {@code /api/auth/profile}). ITs that touch editor content run once per constant
 * ({@code @ParameterizedTest @EnumSource(EditorMode.class)}), so a new mode added here is covered automatically.
 */
public enum EditorMode {

    /** Plain CodeMirror source: every character of markup is on screen. */
    SOURCE( "source" ),

    /** Live preview: markup off the caret's line is hidden or replaced by widgets ({@code .cm-editor.cm-live-preview}). */
    LIVE( "live" );

    private final String id;

    EditorMode( final String id ) {
        this.id = id;
    }

    /** The mode's wire name: what the app stores and what {@code /api/auth/profile} accepts. */
    public String id() {
        return id;
    }
}
