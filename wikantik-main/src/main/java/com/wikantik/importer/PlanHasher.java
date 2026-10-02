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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.HexFormat;
import java.util.TreeSet;

/** The plan hash: a digest of everything that decides the plan, so apply can refuse a stale one (409). */
public final class PlanHasher {

    private PlanHasher() {
    }

    /**
     * Folds in the planned cluster outcomes (slug, JOIN/CREATE, hub page), so a hub declared by someone else between
     * plan and apply changes the hash.
     */
    public static String hash( final String zipSha256, final ImportOptions options, final Collection< String > collidedNames,
                              final Collection< PlannedCluster > clusters ) {
        final String cluster = options.cluster() == null ? "" : options.cluster();
        final String text = zipSha256 + "\n" + options.mode() + "\n" + cluster + "\n"
            + String.join( "\n", new TreeSet<>( collidedNames ) ) + "\n"
            + clusters.stream().map( c -> c.cluster() + "=" + c.action() + "=" + c.hubPage() ).sorted()
                .collect( Collectors.joining( "\n" ) );
        try {
            return HexFormat.of().formatHex( MessageDigest.getInstance( "SHA-256" ).digest( text.getBytes( StandardCharsets.UTF_8 ) ) );
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 is required by the Java platform", e );
        }
    }
}
