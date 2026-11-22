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
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.work.Attachment;
import com.wikantik.work.AttachmentListing;
import com.wikantik.work.FileKinds;

import java.util.function.Function;

/**
 * The JSON of an attachment: the stored record shown as {@link WorkJson} shows people (a display name for a
 * non-admin, never the login), without the content hash, the item id or the removal stamp, plus the route that
 * fetches it, whether it is shown inline, and the Markdown a comment or description uses to refer to it.
 */
final class WorkAttachmentJson {

    private WorkAttachmentJson() {
    }

    /** {@code tree} turns the record into its name-enriched JSON tree. */
    static JsonObject attachment( final Attachment a, final String itemKey,
                                  final Function< Object, JsonElement > tree ) {
        final JsonObject o = tree.apply( a ).getAsJsonObject();
        o.remove( "sha256" );
        o.remove( "itemId" );
        o.remove( "removedAt" );
        o.addProperty( "url", "/api/work/items/" + itemKey + "/attachments/" + a.id() );
        o.addProperty( "inline", FileKinds.inline( a.contentType() ) );
        o.addProperty( "markdown", markdown( a ) );
        return o;
    }

    /**
     * {@code {"attachments":[…],"mayAttach":b,"maxFileBytes":n,"maxItemBytes":n,"usedBytes":n}}: the live list plus
     * what a client needs to offer an upload (see {@link AttachmentListing}).
     */
    static JsonObject list( final AttachmentListing listing, final String itemKey,
                            final Function< Object, JsonElement > tree ) {
        final JsonArray array = new JsonArray();
        for ( final Attachment a : listing.attachments() ) {
            array.add( attachment( a, itemKey, tree ) );
        }
        final JsonObject o = new JsonObject();
        o.add( "attachments", array );
        o.addProperty( "mayAttach", listing.mayAttach() );
        o.addProperty( "maxFileBytes", listing.maxFileBytes() );
        o.addProperty( "maxItemBytes", listing.maxItemBytes() );
        o.addProperty( "usedBytes", listing.usedBytes() );
        return o;
    }

    /** {@code ![name](work-attachment:ID)} for an image shown inline, else {@code [name](work-attachment:ID)}. */
    static String markdown( final Attachment a ) {
        final String name = a.filename().replace( "\\", "\\\\" ).replace( "[", "\\[" ).replace( "]", "\\]" );
        return ( FileKinds.inline( a.contentType() ) ? "!" : "" ) + "[" + name + "](work-attachment:" + a.id() + ")";
    }
}
