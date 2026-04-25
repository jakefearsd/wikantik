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
package com.wikantik.extractcli.mainpage;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code Main.pins.yaml} file into a {@link PinsConfig}. Permissive on
 * shape — missing top-level keys default to empty values; malformed entries
 * throw {@link IllegalArgumentException} with a path hint so authors can fix
 * them quickly.
 */
public final class PinsParser {

    private PinsParser() {}

    public static PinsConfig parse( final Path file ) throws IOException {
        return parse( Files.readString( file ) );
    }

    @SuppressWarnings( "unchecked" )
    public static PinsConfig parse( final String yaml ) {
        if ( yaml == null || yaml.isBlank() ) {
            return new PinsConfig( "", "", List.of() );
        }
        final Object root = new Yaml().load( yaml );
        if ( root == null ) {
            return new PinsConfig( "", "", List.of() );
        }
        if ( !( root instanceof Map ) ) {
            throw new IllegalArgumentException(
                    "Main.pins.yaml: root must be a mapping, got " + root.getClass().getSimpleName() );
        }
        final Map< String, Object > map = (Map< String, Object >) root;
        final String intro  = stringOrEmpty( map.get( "intro" ) );
        final String footer = stringOrEmpty( map.get( "footer" ) );
        final Object sectionsRaw = map.get( "sections" );
        final List< PinsConfig.PinsSection > sections = parseSections( sectionsRaw );
        return new PinsConfig( intro, footer, sections );
    }

    @SuppressWarnings( "unchecked" )
    private static List< PinsConfig.PinsSection > parseSections( final Object raw ) {
        if ( raw == null ) {
            return List.of();
        }
        if ( !( raw instanceof List ) ) {
            throw new IllegalArgumentException( "Main.pins.yaml: sections must be a list" );
        }
        final List< ? > list = (List< ? >) raw;
        final List< PinsConfig.PinsSection > out = new ArrayList<>( list.size() );
        for ( int i = 0; i < list.size(); i++ ) {
            final Object item = list.get( i );
            if ( !( item instanceof Map ) ) {
                throw new IllegalArgumentException(
                        "Main.pins.yaml: sections[" + i + "] must be a mapping" );
            }
            final Map< String, Object > section = (Map< String, Object >) item;
            final String label = stringOrEmpty( section.get( "label" ) );
            if ( label.isBlank() ) {
                throw new IllegalArgumentException(
                        "Main.pins.yaml: sections[" + i + "] is missing a label" );
            }
            final String cluster = stringOrNull( section.get( "cluster" ) );
            final Object pagesRaw = section.get( "pages" );
            final List< PinsConfig.PinsPage > pages = parsePages( pagesRaw, i );
            out.add( new PinsConfig.PinsSection( label, cluster, pages ) );
        }
        return out;
    }

    @SuppressWarnings( "unchecked" )
    private static List< PinsConfig.PinsPage > parsePages( final Object raw, final int sectionIndex ) {
        if ( raw == null ) {
            return List.of();
        }
        if ( !( raw instanceof List ) ) {
            throw new IllegalArgumentException(
                    "Main.pins.yaml: sections[" + sectionIndex + "].pages must be a list" );
        }
        final List< ? > list = (List< ? >) raw;
        final List< PinsConfig.PinsPage > out = new ArrayList<>( list.size() );
        for ( int i = 0; i < list.size(); i++ ) {
            final Object item = list.get( i );
            if ( item instanceof String shortForm ) {
                // Short form: bare canonical_id with no override.
                if ( shortForm.isBlank() ) {
                    throw new IllegalArgumentException(
                            "Main.pins.yaml: sections[" + sectionIndex + "].pages[" + i + "] is blank" );
                }
                out.add( new PinsConfig.PinsPage( shortForm.trim(), null, null ) );
                continue;
            }
            if ( !( item instanceof Map ) ) {
                throw new IllegalArgumentException(
                        "Main.pins.yaml: sections[" + sectionIndex + "].pages[" + i + "] must be a mapping or string" );
            }
            final Map< String, Object > page = (Map< String, Object >) item;
            // Accept both "id" and "canonical_id" for readability.
            final Object idRaw = page.containsKey( "id" ) ? page.get( "id" ) : page.get( "canonical_id" );
            final String id = stringOrNull( idRaw );
            if ( id == null || id.isBlank() ) {
                throw new IllegalArgumentException(
                        "Main.pins.yaml: sections[" + sectionIndex + "].pages[" + i + "] is missing id/canonical_id" );
            }
            final String title   = stringOrNull( page.get( "title" ) );
            final String summary = stringOrNull( page.get( "summary" ) );
            out.add( new PinsConfig.PinsPage( id, title, summary ) );
        }
        return out;
    }

    private static String stringOrEmpty( final Object o ) {
        return o == null ? "" : o.toString();
    }

    private static String stringOrNull( final Object o ) {
        if ( o == null ) {
            return null;
        }
        final String s = o.toString();
        return s.isEmpty() ? null : s;
    }
}
