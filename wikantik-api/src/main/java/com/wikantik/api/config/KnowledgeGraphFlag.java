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
package com.wikantik.api.config;

import java.util.Properties;

import com.wikantik.util.TextUtil;

/**
 * The Knowledge Graph master switch ({@code wikantik.knowledge.enabled}) — the single reader of that
 * key. The Knowledge Graph (LLM-extracted entities, {@code kg_nodes}/{@code kg_edges}/proposals,
 * KG policy, hub discovery) was retired 2026-11-08 and is OFF by default; when off, nothing reads
 * the KG tables.
 */
public final class KnowledgeGraphFlag {

    /** Configuration property name. */
    public static final String PROPERTY = "wikantik.knowledge.enabled";

    /** Shipped default: the Knowledge Graph is off. */
    public static final boolean DEFAULT = false;

    private KnowledgeGraphFlag() {}

    /**
     * @param props configuration properties; may be {@code null}
     * @return whether the Knowledge Graph subsystem is enabled; absent, blank or {@code null}
     *         properties yield {@link #DEFAULT}
     */
    public static boolean isEnabled( final Properties props ) {
        if ( props == null ) {
            return DEFAULT;
        }
        // Literal default on purpose: ConfigSurfaceDriftTest compares it with the ini file's value.
        return TextUtil.getBooleanProperty( props, PROPERTY, false );
    }
}
