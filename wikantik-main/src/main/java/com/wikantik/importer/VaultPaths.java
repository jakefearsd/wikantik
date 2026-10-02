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
package com.wikantik.importer;

import java.util.Comparator;
import java.util.Locale;

/** Pure helpers over {@code /}-separated vault paths. */
public final class VaultPaths {

    /** Case-insensitive, then natural, ordering used wherever vault paths are sorted. */
    public static final Comparator< String > ORDER =
        String.CASE_INSENSITIVE_ORDER.thenComparing( Comparator.naturalOrder() );

    private static final String MD = ".md";

    private VaultPaths() {
    }

    /** {@code "a/b/C.md"} becomes {@code "C.md"}. */
    public static String basename( final String path ) {
        return path.substring( path.lastIndexOf( '/' ) + 1 );
    }

    /** {@code "a/b/C.md"} becomes {@code "a/b"}; a root-level path becomes {@code ""}. */
    public static String parentFolder( final String path ) {
        final int slash = path.lastIndexOf( '/' );
        return slash < 0 ? "" : path.substring( 0, slash );
    }

    /** Strips a trailing {@code .md}, case-insensitively. */
    public static String withoutMd( final String path ) {
        return isNote( path ) ? path.substring( 0, path.length() - MD.length() ) : path;
    }

    /** True when the path ends with {@code .md}, case-insensitively. */
    public static boolean isNote( final String path ) {
        return path.toLowerCase( Locale.ROOT ).endsWith( MD );
    }
}
