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

import java.time.Duration;

/**
 * Tunables of the work service: how long a claim lasts, the largest attachment and the most attachment bytes an item
 * may hold (live ones only).
 */
public record WorkSettings( Duration claimExpiry, long maxAttachmentBytes, long maxItemAttachmentBytes ) {
    /** Default per-file limit: 10 MB. */
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10_485_760L;
    /** Default per-item limit: 50 MB. */
    public static final long DEFAULT_MAX_ITEM_ATTACHMENT_BYTES = 52_428_800L;

    public static final WorkSettings DEFAULTS = new WorkSettings( Duration.ofMinutes( 240 ),
        DEFAULT_MAX_ATTACHMENT_BYTES, DEFAULT_MAX_ITEM_ATTACHMENT_BYTES );

    /** The claim expiry given and the default attachment limits. */
    public WorkSettings( final Duration claimExpiry ) {
        this( claimExpiry, DEFAULT_MAX_ATTACHMENT_BYTES, DEFAULT_MAX_ITEM_ATTACHMENT_BYTES );
    }
}
