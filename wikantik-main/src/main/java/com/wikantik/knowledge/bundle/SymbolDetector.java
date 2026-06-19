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
package com.wikantik.knowledge.bundle;

import java.util.regex.Pattern;

/**
 * Cheap, high-precision detector for queries that literally name a code symbol — used as a
 * confidence <em>booster</em> for injection (NOT a router). Fires on a camelCase token with an
 * internal case change ({@code HybridChunkSectionSource}, {@code getUserId}), a snake_case
 * token ({@code kg_content_chunks}), or a dotted key with ≥2 dots ({@code a.b.c}). Deliberately
 * does NOT fire on a single Capitalized word (sentence case / proper nouns).
 */
public final class SymbolDetector {
    private static final Pattern CAMEL = Pattern.compile("[a-z][A-Za-z]*[A-Z][A-Za-z]*");
    private static final Pattern SNAKE = Pattern.compile("[A-Za-z0-9]+_[A-Za-z0-9_]+");
    private static final Pattern DOTTED = Pattern.compile("[A-Za-z0-9]+\\.[A-Za-z0-9]+\\.[A-Za-z0-9.]+");

    private SymbolDetector() {}

    public static boolean hasCodeSymbol(final String query) {
        if (query == null || query.isBlank()) return false;
        return CAMEL.matcher(query).find() || SNAKE.matcher(query).find() || DOTTED.matcher(query).find();
    }
}
