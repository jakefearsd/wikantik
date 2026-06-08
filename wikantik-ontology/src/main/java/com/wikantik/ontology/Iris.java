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
package com.wikantik.ontology;

import java.util.Locale;
import java.util.UUID;

/** Canonical IRI namespace + builders for the Wikantik ontology. */
public final class Iris {

    private Iris() {}

    public static final String NS     = "https://wiki.wikantik.com/ns/wikantik#";
    public static final String ID     = "https://wiki.wikantik.com/id/";
    public static final String SCHEMA = "https://schema.org/";

    /** Ontology term (class/property), e.g. term("Technology") -> wk:Technology. */
    public static String term( final String localName ) {
        return NS + localName;
    }

    /** Entity instance IRI keyed on the kg_nodes UUID. */
    public static String entity( final UUID id ) {
        return ID + "entity/" + id;
    }

    /** Page instance IRI keyed on the rename-stable canonical_id (ULID). */
    public static String page( final String canonicalId ) {
        return ID + "page/" + canonicalId;
    }

    /** SKOS concept (tag/cluster) IRI: lowercase, whitespace collapsed to '-', '/' kept for sub-cluster paths. */
    public static String concept( final String value ) {
        final String slug = value.trim().toLowerCase( Locale.ROOT )
                .replaceAll( "\\s+", "-" )
                .replaceAll( "[^a-z0-9/-]", "" )
                .replaceAll( "-{2,}", "-" );
        return ID + "concept/" + slug;
    }
}
