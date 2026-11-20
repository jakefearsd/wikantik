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
package com.wikantik.connectors.config;

import com.google.gson.JsonObject;

/**
 * Validation and JSON &lt;-&gt; typed-config translation for one admin-creatable connector type.
 * Implementations are stateless singletons registered in {@link ConnectorConfigCodec}, which looks
 * them up by {@link #type()} via {@link ConnectorConfigCodec#codecFor(String)}.
 *
 * @param <C> the typed config record this connector type consumes
 */
public interface ConnectorTypeCodec< C > {

    /** The connector type string this codec handles, e.g. {@code "feed"}; its registry key. */
    String type();

    /** The typed config record {@link #build} produces and {@link #toJson} accepts. */
    Class< C > configClass();

    /** Field-keyed validation of {@code config}; {@link ConnectorConfigCodec.Validation#ok()} when valid. */
    ConnectorConfigCodec.Validation validate( JsonObject config );

    /** Builds the typed config with defaults applied; {@code forTest} clamps the dry-run caps.
     *  Callers must have validated {@code config} first. */
    C build( JsonObject config, boolean forTest );

    /** Serializes a typed config back to this codec's JSON field names; the inverse of {@link #build}. */
    JsonObject toJson( C config );
}
