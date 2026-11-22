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
 * The notification tunables the host reads from configuration. Time of day is the work clock's zone (the server's).
 *
 * @param dispatchInterval how often the dispatcher runs
 * @param emailEnabled     whether email may be sent at all
 * @param emailDetail      how much a mailed notification says
 * @param emailInterval    the least time between email runs
 * @param dailyHour        the hour (0-23) after which a daily digest goes out
 * @param baseUrl          the wiki's absolute base URL, no trailing slash; blank means links are paths
 * @param webhooksEnabled  whether webhooks may be added and delivered
 * @param retentionDays    days a notification is kept, read or not; 0 keeps them for ever
 */
public record NotifySettings( Duration dispatchInterval, boolean emailEnabled, EmailDetail emailDetail,
                              Duration emailInterval, int dailyHour, String baseUrl, boolean webhooksEnabled, int retentionDays ) {

    /** The retention a wiki gets unless it sets one. */
    public static final int DEFAULT_RETENTION_DAYS = 180;

    /** Without a retention: {@link #DEFAULT_RETENTION_DAYS}. */
    public NotifySettings( final Duration dispatchInterval, final boolean emailEnabled, final EmailDetail emailDetail,
                           final Duration emailInterval, final int dailyHour, final String baseUrl,
                           final boolean webhooksEnabled ) {
        this( dispatchInterval, emailEnabled, emailDetail, emailInterval, dailyHour, baseUrl, webhooksEnabled,
            DEFAULT_RETENTION_DAYS );
    }


    /** Everything off: what a wiki with nothing wired gets. */
    public static final NotifySettings OFF = new NotifySettings( Duration.ofSeconds( 60 ), false,
        EmailDetail.SUMMARY, Duration.ofMinutes( 5 ), 8, "", false );
}
