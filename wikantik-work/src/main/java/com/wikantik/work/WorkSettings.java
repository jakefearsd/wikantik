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
 * may hold (live ones only), what a reporter may upload to one item, and the most requests a reporter files a day.
 */
public record WorkSettings( Duration claimExpiry, long maxAttachmentBytes, long maxItemAttachmentBytes,
                            long reporterItemAttachmentBytes, int intakeMaxPerDay ) {
    /** Default per-file limit: 10 MB. */
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10_485_760L;
    /** Default per-item limit: 50 MB. */
    public static final long DEFAULT_MAX_ITEM_ATTACHMENT_BYTES = 52_428_800L;

    /** Default of what a person who only reports may upload to one of their items: 10 MiB. */
    public static final long DEFAULT_REPORTER_ITEM_ATTACHMENT_BYTES = 10_485_760L;
    /** Default cap on the requests one reporter files in a rolling 24 hours: 20 (0 means no cap). */
    public static final int DEFAULT_INTAKE_MAX_PER_DAY = 20;

    public static final WorkSettings DEFAULTS = new WorkSettings( Duration.ofMinutes( 240 ),
        DEFAULT_MAX_ATTACHMENT_BYTES, DEFAULT_MAX_ITEM_ATTACHMENT_BYTES );

    /** The claim expiry and attachment limits given, and the default reporter limits. */
    public WorkSettings( final Duration claimExpiry, final long maxAttachmentBytes, final long maxItemAttachmentBytes ) {
        this( claimExpiry, maxAttachmentBytes, maxItemAttachmentBytes, DEFAULT_REPORTER_ITEM_ATTACHMENT_BYTES,
            DEFAULT_INTAKE_MAX_PER_DAY );
    }

    /** The claim expiry given and the default attachment limits. */
    public WorkSettings( final Duration claimExpiry ) {
        this( claimExpiry, DEFAULT_MAX_ATTACHMENT_BYTES, DEFAULT_MAX_ITEM_ATTACHMENT_BYTES );
    }
}
