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

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * A project webhook as stored. It carries the URL and the secret, so it never leaves the module: views and
 * {@link #toString()} show {@link #target()} only (scheme and host, never the path, which may itself be a credential).
 *
 * @param secret   the HMAC key as issued (the signature needs the raw key); null for Slack
 * @param cursorTx the transaction id half of the delivery cursor, as text
 */
public record Webhook( long id, long projectId, URI url, WebhookFormat format, String secret,
                       List< String > eventTypes, String owner, Instant createdAt, String cursorTx,
                       long cursorEventId, Instant failingSince, String lastError, Instant lastDeliveredAt,
                       Instant disabledAt ) {

    public Webhook {
        eventTypes = eventTypes == null ? List.of() : List.copyOf( eventTypes );
    }

    /** {@code scheme://host/…}: where it goes, without the path, query or any user-info. */
    public String target() {
        return url.getScheme() + "://" + url.getHost() + "/…";
    }

    @Override
    public String toString() {
        return "Webhook[id=" + id + ", project=" + projectId + ", target=" + target() + ", format=" + format.wire() + "]";
    }
}
