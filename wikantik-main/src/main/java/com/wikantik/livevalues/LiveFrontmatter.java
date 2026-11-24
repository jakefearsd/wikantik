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
package com.wikantik.livevalues;

import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.livevalues.LiveValues;

import java.util.LinkedHashMap;
import java.util.Map;

/** Expands live-value tokens in the one frontmatter field that may carry them, {@code summary}. */
public final class LiveFrontmatter {

    private LiveFrontmatter() {
    }

    /**
     * @return a copy of {@code parsed} whose {@code summary} has its tokens expanded and everything else untouched;
     *         {@code parsed} itself (same instance) when {@code lv} is null or there is no string summary
     */
    public static ParsedPage expandSummary( final ParsedPage parsed, final LiveValues lv ) {
        if( lv == null || parsed == null || !( parsed.metadata().get( "summary" ) instanceof String summary ) ) {
            return parsed;
        }
        final String expanded = lv.expandText( summary );
        if( summary.equals( expanded ) ) {
            return parsed;
        }
        final Map< String, Object > copy = new LinkedHashMap<>( parsed.metadata() );
        copy.put( "summary", expanded );
        return new ParsedPage( copy, parsed.body() );
    }
}
