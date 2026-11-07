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
package com.wikantik.collab;

import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.StoredMetaOp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A14's meta edit rules: validation, and the resolution of an edit to the last-writer-wins assignment the session
 * stores (ruling Q1: set ops are resolved server-side, under the session lock, so the meta-op log keeps its shape).
 * Every value is compared after {@link MetaJson} normalisation on both sides, so a YAML {@code Date} and its JSON
 * string are one value. Set ops (ADD, REMOVE) on {@link #SET_FIELDS} treat an absent field as an empty list and a
 * scalar as a one-item list (never split on commas); an ADD of a present item or a REMOVE of an absent one changes
 * nothing. The items of a set field compare and are stored as strings, whichever op writes them (rulings Q7 and fix
 * round 1): YAML {@code tags: [2024]} holds the item {@code "2024"}. Every other field compares normalised JSON. A
 * resolved value whose normalised JSON exceeds {@link #MAX_VALUE_BYTES} UTF-8 bytes refuses the push (ruling Q8).
 */
final class MetaEdits {

    /** The fields whose values are sets of strings: the only ones ADD and REMOVE may target. */
    static final Set< String > SET_FIELDS = Set.of( "tags", "related", "aliases" );
    /** The largest resolved meta value, in UTF-8 bytes of its normalised JSON (64 KiB). */
    static final int MAX_VALUE_BYTES = 64 * 1024;
    private static final int MAX_FIELD_LENGTH = 128;
    /** The raw-YAML pseudo-field of the frontmatter editor, never a page field. */
    private static final String RAW_YAML = "__yaml__";

    private MetaEdits() {
    }

    /** Null when {@code e} may be applied, else why not. */
    static String invalid( final MetaEdit e ) {
        if( e == null ) {
            return "a meta edit is missing";
        }
        final String f = e.field();
        if( f == null || f.isBlank() ) {
            return "a meta edit needs a field";
        }
        if( f.length() > MAX_FIELD_LENGTH ) {
            return "field names are at most " + MAX_FIELD_LENGTH + " characters";
        }
        if( RAW_YAML.equals( f ) ) {
            return RAW_YAML + " is not a field";
        }
        if( e.kind() != MetaEdit.Kind.SET && !SET_FIELDS.contains( f ) ) {
            return "add and remove are allowed only on tags, related and aliases";
        }
        return null;
    }

    /** The normalised value of the field after {@code e}, given its {@code current} value (null when absent). */
    static Object resolve( final Object current, final MetaEdit e ) {
        if( e.kind() == MetaEdit.Kind.SET ) {
            final Object v = normalise( e.value() );
            return SET_FIELDS.contains( e.field() ) ? asStrings( v ) : v;
        }
        final Object cur = normalise( current );
        final Object item = e.value();
        final List< Object > items = new ArrayList<>();
        if( cur instanceof List< ? > l ) {
            l.forEach( x -> items.add( text( x ) ) );
        } else if( cur != null ) {
            items.add( text( cur ) );
        }
        final boolean present = items.contains( item );
        if( e.kind() == MetaEdit.Kind.ADD ) {
            if( present ) {
                return cur;
            }
            items.add( item );
        } else {
            if( !present ) {
                return cur;
            }
            items.removeIf( item::equals );
        }
        return items;
    }

    /**
     * The meta ops for {@code edits}, resolved in request order against {@code meta} (not modified) and numbered from
     * {@code metaSeq + 1}; an edit that leaves its field's normalised value unchanged yields no op. The edits must be
     * valid ({@link #invalid}).
     */
    static List< StoredMetaOp > ops( final Map< String, Object > meta, final List< MetaEdit > edits, final long metaSeq,
                                     final String authorKey ) {
        final Map< String, Object > scratch = new LinkedHashMap<>( meta );
        final List< StoredMetaOp > ops = new ArrayList<>();
        for( final MetaEdit e : edits ) {
            final Object before = normalise( scratch.get( e.field() ) );
            final Object after = resolve( before, e );
            final boolean set = SET_FIELDS.contains( e.field() );
            if( set ? Objects.equals( asStrings( before ), asStrings( after ) ) : Objects.equals( before, after ) ) {
                continue;
            }
            final StoredMetaOp op = new StoredMetaOp( metaSeq + ops.size() + 1, authorKey, e.field(), after );
            SessionLoader.applyMetaOp( scratch, op );
            ops.add( op );
        }
        return ops;
    }

    /** The field of the first op whose value's normalised JSON exceeds {@link #MAX_VALUE_BYTES}, or null. */
    static String oversized( final List< StoredMetaOp > ops ) {
        for( final StoredMetaOp op : ops ) {
            if( MetaJson.GSON.toJson( op.value() ).getBytes( StandardCharsets.UTF_8 ).length > MAX_VALUE_BYTES ) {
                return op.field();
            }
        }
        return null;
    }

    /** A set field's value with its items as strings: a list item by item, a scalar as one string, null as null. */
    private static Object asStrings( final Object v ) {
        if( v instanceof List< ? > l ) {
            final List< Object > out = new ArrayList<>( l.size() );
            l.forEach( x -> out.add( text( x ) ) );
            return out;
        }
        return text( v );
    }

    /** One set item as a string: strings as they are, numbers and booleans as written, anything else as its JSON. */
    private static String text( final Object x ) {
        if( x == null || x instanceof String ) {
            return (String) x;
        }
        return x instanceof Number || x instanceof Boolean ? x.toString() : MetaJson.GSON.toJson( x );
    }

    private static Object normalise( final Object value ) {
        return MetaJson.normaliseValue( value );
    }
}
