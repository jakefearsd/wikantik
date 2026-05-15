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
package com.wikantik.llm.activity;

import java.util.Properties;
import com.wikantik.util.TextUtil;

/**
 * Process-wide handle for the single {@link LlmActivityLog}. Mirrors the
 * {@code MeterRegistryHolder} pattern: the log is a cross-cutting observability
 * singleton reached both by the recording decorators (during subsystem wiring)
 * and by the admin servlet. Not persisted.
 */
public final class LlmActivityLogHolder {

    /** Returned before any log is installed, so callers never see {@code null}. */
    private static final LlmActivityLog DISABLED = new LlmActivityLog( false, 60, 1, 500 );

    private static volatile LlmActivityLog instance;

    private LlmActivityLogHolder() {}

    /**
     * Returns the installed log, creating it from {@code props} on first call.
     * Idempotent — later calls return the same instance and ignore {@code props}.
     */
    public static synchronized LlmActivityLog getOrCreate( final Properties props ) {
        if ( instance == null ) {
            final boolean enabled = TextUtil.getBooleanProperty(
                props, "wikantik.llm_activity.enabled", true );
            final int window = TextUtil.getIntegerProperty(
                props, "wikantik.llm_activity.window_minutes", 60 );
            final int maxRecords = TextUtil.getIntegerProperty(
                props, "wikantik.llm_activity.max_records", 5000 );
            final int payloadChars = TextUtil.getIntegerProperty(
                props, "wikantik.llm_activity.payload_chars", 500 );
            instance = new LlmActivityLog( enabled, window, maxRecords, payloadChars );
        }
        return instance;
    }

    /** The installed log, or a permanently-disabled fallback if none is installed yet. */
    public static LlmActivityLog get() {
        final LlmActivityLog i = instance;
        return i != null ? i : DISABLED;
    }

    /** Test-only: install a specific log (or {@code null} to reset). */
    static void setForTesting( final LlmActivityLog log ) {
        instance = log;
    }
}
