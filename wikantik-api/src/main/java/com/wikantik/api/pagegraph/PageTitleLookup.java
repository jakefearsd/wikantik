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
package com.wikantik.api.pagegraph;

import java.util.Collection;
import java.util.List;

/** Title/alias lookup over the structural projection: drives page-name search ranking and mention scanning. */
public interface PageTitleLookup {

    /** One page's matchable phrases: de-CamelCased name, frontmatter title (if it differs), aliases. */
    record TitleEntry( String slug, String title, List< String > phrases ) {}

    /**
     * Ranks {@code names} against {@code query} using each page's name, de-CamelCased name, title and aliases.
     * Tiers (best key wins): exact, prefix, substring, subsequence — comparisons are case- and
     * whitespace-insensitive. Non-matching names are dropped; ties break by name (natural order).
     * A blank query returns all names in natural order.
     */
    List< String > rank( Collection< String > names, String query );

    /** Every indexed page, for phrase matching (mention scan). */
    List< TitleEntry > entries();
}
