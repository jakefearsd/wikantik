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
package com.wikantik.mcp.tools;

import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;

import java.util.Map;

/** The save-warning sinks' clear-before and drain-after sequence shared by {@code update_page} and {@code write_pages}. */
final class SaveWarnings {

    private SaveWarnings() {
    }

    /** Empties both sinks so a save starts with no warnings from an earlier one. */
    static void clear() {
        FrontmatterWarningSink.clear();
        ContentWarningSink.clear();
    }

    /** Drains both sinks into {@code entry} as {@code frontmatterWarnings} then {@code mathWarnings}, when non-empty. */
    static void attach( final Map< String, Object > entry, final String pageName ) {
        attach( entry, pageName, java.util.List.of() );
    }

    /** As {@link #attach(Map, String)}, with {@code extra} warnings (computed by the tool) listed after the sink's. */
    static void attach( final Map< String, Object > entry, final String pageName,
                        final java.util.List< com.wikantik.api.frontmatter.schema.FieldViolation > extra ) {
        final var fmWarnings = new java.util.ArrayList<>( FrontmatterWarningSink.drain( pageName ) );
        fmWarnings.addAll( extra );
        if ( !fmWarnings.isEmpty() ) {
            entry.put( "frontmatterWarnings", fmWarnings );
        }
        final var mathWarnings = ContentWarningSink.drain();
        if ( !mathWarnings.isEmpty() ) {
            entry.put( "mathWarnings", mathWarnings );
        }
    }
}
