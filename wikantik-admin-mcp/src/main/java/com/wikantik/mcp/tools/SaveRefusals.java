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

import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the refusals a page save can raise to the result fields shared by {@code update_page} and
 * {@code write_pages}. Each map starts at the outcome flag ({@code created} or {@code updated}, false);
 * the caller supplies {@code pageName} ahead of it, so key order matches the tools' historical output.
 */
final class SaveRefusals {

    private static final String PARSE_HINT = "Wrap values containing ':' or other YAML special "
        + "characters in double quotes, e.g. title: \"Foo: Bar\". Or pass the "
        + "fields as the structured metadata argument and omit the YAML block "
        + "from content.";

    private SaveRefusals() {
    }

    /**
     * Maps a save refusal to its result fields, clearing the warning sinks that refusal leaves stale.
     *
     * @param t the thrown exception
     * @param flagKey {@code "created"} or {@code "updated"}
     * @return the fields, or {@code null} when {@code t} is not one of the three refusal types
     */
    static Map< String, Object > refusalFor( final Throwable t, final String flagKey ) {
        final Map< String, Object > refused = new LinkedHashMap<>();
        refused.put( flagKey, false );
        if ( t instanceof ContentValidationException cve ) {
            // Math validator refused this write (ERROR-severity).
            ContentWarningSink.clear();
            refused.put( "error", "math validation failed" );
            refused.put( "violations", cve.violations() );
        } else if ( t instanceof AccessRuleShapeException are ) {
            // The write would add an access rule the wiki cannot read, locking the page to administrators (#104).
            FrontmatterWarningSink.clear();
            ContentWarningSink.clear();
            refused.put( "error", "access rule shape refused" );
            refused.put( "violations", are.violations() );
            refused.put( "total", are.total() );
        } else if ( t instanceof FrontmatterValidationException fve ) {
            // Same schema validator the form + REST use refused this write (ERROR-severity).
            FrontmatterWarningSink.clear();
            refused.put( "error", "frontmatter validation failed" );
            refused.put( "violations", fve.violations() );
        } else {
            return null;
        }
        return refused;
    }

    /** Maps a frontmatter parse failure to its result fields, with the line, column and fix hint. */
    static Map< String, Object > parseError( final FrontmatterParseException e, final String flagKey ) {
        final Map< String, Object > fail = new LinkedHashMap<>();
        fail.put( flagKey, false );
        fail.put( "error", "frontmatter parse error: " + e.getMessage() );
        if ( e.line() > 0 ) {
            fail.put( "frontmatterLine", e.line() );
        }
        if ( e.column() > 0 ) {
            fail.put( "frontmatterColumn", e.column() );
        }
        fail.put( "hint", PARSE_HINT );
        return fail;
    }
}
