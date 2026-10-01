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
package com.wikantik.markdown.extensions.callouts;

import java.util.Locale;
import java.util.Map;

/** Obsidian callout types and aliases → the 13 visual styles. */
public final class CalloutTypes {
    private static final Map< String, String > STYLE = Map.ofEntries(
            Map.entry( "note", "note" ),
            Map.entry( "abstract", "abstract" ), Map.entry( "summary", "abstract" ), Map.entry( "tldr", "abstract" ),
            Map.entry( "info", "info" ),
            Map.entry( "todo", "todo" ),
            Map.entry( "tip", "tip" ), Map.entry( "hint", "tip" ), Map.entry( "important", "tip" ),
            Map.entry( "success", "success" ), Map.entry( "check", "success" ), Map.entry( "done", "success" ),
            Map.entry( "question", "question" ), Map.entry( "help", "question" ), Map.entry( "faq", "question" ),
            Map.entry( "warning", "warning" ), Map.entry( "caution", "warning" ), Map.entry( "attention", "warning" ),
            Map.entry( "failure", "failure" ), Map.entry( "fail", "failure" ), Map.entry( "missing", "failure" ),
            Map.entry( "danger", "danger" ), Map.entry( "error", "danger" ),
            Map.entry( "bug", "bug" ),
            Map.entry( "example", "example" ),
            Map.entry( "quote", "quote" ), Map.entry( "cite", "quote" ) );

    private CalloutTypes() {}

    public static String styleOf( final String rawType ) {
        return STYLE.getOrDefault( rawType.toLowerCase( Locale.ROOT ), "note" );
    }

    public static String defaultTitle( final String rawType ) {
        return rawType.isEmpty() ? "" : Character.toUpperCase( rawType.charAt( 0 ) ) + rawType.substring( 1 );
    }
}
