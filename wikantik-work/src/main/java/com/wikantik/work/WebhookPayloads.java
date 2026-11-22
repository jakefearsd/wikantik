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
package com.wikantik.work;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Function;

/**
 * What a webhook sends. Only the event's type, time, actor (display name), a neutral sentence, and the item's key,
 * title, status, type and link: never a note, a raw value, a body, comment text or a login. Sprint events and
 * project-level events are not sendable (no item), nor is {@code comment_edited} (its notice text speaks to "you").
 */
final class WebhookPayloads {

    /** The event types a webhook may name; an empty list on a webhook means all of these. */
    static final Set< String > SENDABLE = Set.of( WorkEvent.CREATED, WorkEvent.EDITED, WorkEvent.STATUS,
        WorkEvent.ASSIGNED, WorkEvent.HANDOFF, WorkEvent.FIELD, WorkEvent.COMMENTED, WorkEvent.LINKED,
        WorkEvent.UNLINKED, WorkEvent.CLAIMED, WorkEvent.RELEASED, WorkEvent.CLAIM_EXPIRED, WorkEvent.ACKNOWLEDGED,
        WorkEvent.CHANGE_OPENED, WorkEvent.CHANGE_UPDATED, WorkEvent.REVIEW_REQUESTED, WorkEvent.CHANGE_REVIEWED,
        WorkEvent.CHANGE_MERGED, WorkEvent.CHANGE_ABANDONED, WorkEvent.DEPLOYMENT_REQUESTED, WorkEvent.SHIPPED,
        WorkEvent.ROLLED_BACK );

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private WebhookPayloads() {
    }

    static String generic( final WebhookEvent w, final String baseUrl, final Function< String, String > displayName ) {
        final WorkEvent e = w.event();
        final JsonObject event = new JsonObject();
        event.addProperty( "id", e.id() );
        event.addProperty( "type", e.type() );
        event.addProperty( "at", e.at().toString() );
        event.addProperty( "actor", actor( e, displayName ) );
        event.addProperty( "text", NoticeText.line( e, null, null, displayName ) );
        if ( WorkEvent.STATUS.equals( e.type() ) ) {
            event.addProperty( "from", e.fromValue() );
            event.addProperty( "to", e.toValue() );
        }
        final JsonObject item = new JsonObject();
        item.addProperty( "key", e.itemKey() );
        item.addProperty( "title", w.itemTitle() );
        item.addProperty( "status", w.itemStatus().wire() );
        item.addProperty( "url", url( baseUrl, e.itemKey() ) );
        item.addProperty( "type", w.itemType() );
        final JsonObject project = new JsonObject();
        project.addProperty( "key", w.projectKey() );
        final JsonObject root = new JsonObject();
        root.add( "event", event );
        root.add( "item", item );
        root.add( "project", project );
        return GSON.toJson( root );
    }

    static String slack( final WebhookEvent w, final String baseUrl, final Function< String, String > displayName ) {
        final WorkEvent e = w.event();
        final String rest = " " + escape( w.itemTitle() ) + ": "
            + escape( NoticeText.line( e, null, null, displayName ) );
        final String head = baseUrl == null || baseUrl.isBlank() ? e.itemKey()
            : "<" + url( baseUrl, e.itemKey() ) + "|" + e.itemKey() + ">";
        final JsonObject root = new JsonObject();
        root.addProperty( "text", head + rest );
        return GSON.toJson( root );
    }

    /** {@code sha256=} and the lowercase hex HMAC-SHA256 of the body under the secret (both UTF-8). */
    static String signature( final String secret, final String body ) {
        try {
            final Mac mac = Mac.getInstance( "HmacSHA256" );
            mac.init( new SecretKeySpec( secret.getBytes( StandardCharsets.UTF_8 ), "HmacSHA256" ) );
            return "sha256=" + HexFormat.of().formatHex( mac.doFinal( body.getBytes( StandardCharsets.UTF_8 ) ) );
        } catch ( final GeneralSecurityException e ) {
            throw new IllegalStateException( "HmacSHA256 is unavailable", e );
        }
    }

    /** 32 random bytes as hex. */
    static String newSecret() {
        final byte[] bytes = new byte[ SECRET_BYTES ];
        RANDOM.nextBytes( bytes );
        return HexFormat.of().formatHex( bytes );
    }

    private static String actor( final WorkEvent e, final Function< String, String > displayName ) {
        return e.actorKind() == Actor.Kind.AGENT ? e.actor() : displayName.apply( e.actor() );
    }

    private static String url( final String baseUrl, final String key ) {
        return ( baseUrl == null ? "" : baseUrl ) + "/work/item/" + key;
    }

    /** Slack's three control characters, so a title such as {@code <!channel>} cannot ping anyone. */
    private static String escape( final String text ) {
        return text.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
    }
}
