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
package com.wikantik.api.livevalues;

import java.util.Map;

/**
 * Serves live wiki facts for {@code {{wiki:name}}} tokens: expands them in page text and
 * page bodies, and lets value providers register themselves.
 */
public interface LiveValues {

    /** Expands every token in plain text (summaries, descriptions). */
    String expandText( String text );

    /** Expands tokens in Markdown, leaving code (fences, indented blocks, spans) untouched. */
    String expandBody( String markdown );

    /** Changes whenever any value changes; usable in cache keys / ETags. */
    String generation();

    /** Name to exact, unrounded display string, for the admin view and tests. */
    Map< String, String > renderedValues();

    /** Adds a provider of values. */
    void register( LiveValueSource source );
}
