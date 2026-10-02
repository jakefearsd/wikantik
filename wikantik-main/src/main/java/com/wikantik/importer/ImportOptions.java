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

import com.wikantik.api.frontmatter.schema.FrontmatterSchema;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validated import options. */
public record ImportOptions( ClusterMode mode, String cluster ) {

    private static final Pattern SLUG = Pattern.compile( FrontmatterSchema.CLUSTER_SLUG_PATTERN );

    /**
     * Parses request parameters. A null or blank mode means FOLDERS; FIXED needs a cluster matching the
     * cluster slug pattern. Anything else is an {@link IllegalArgumentException} (HTTP 400).
     */
    public static ImportOptions parse( final String mode, final String cluster ) {
        if ( mode == null || mode.isBlank() ) {
            return new ImportOptions( ClusterMode.FOLDERS, null );
        }
        final ClusterMode parsed = switch ( mode.trim().toLowerCase( Locale.ROOT ) ) {
            case "folders" -> ClusterMode.FOLDERS;
            case "fixed" -> ClusterMode.FIXED;
            case "none" -> ClusterMode.NONE;
            default -> throw new IllegalArgumentException( "unknown cluster mode '" + mode + "'" );
        };
        if ( parsed != ClusterMode.FIXED ) {
            return new ImportOptions( parsed, null );
        }
        if ( cluster == null || !SLUG.matcher( cluster ).matches() ) {
            throw new IllegalArgumentException( "cluster '" + cluster + "' is not a valid cluster path" );
        }
        return new ImportOptions( parsed, cluster );
    }
}
