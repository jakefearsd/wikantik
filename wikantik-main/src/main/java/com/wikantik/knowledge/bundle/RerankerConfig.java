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
package com.wikantik.knowledge.bundle;

/** Config for the listwise section reranker. Model defaults to the 4B sweet spot from the
 *  2026-06-13 spike sweep; think:false is enforced by OllamaChatRequest. */
public record RerankerConfig( String model, String baseUrl, long timeoutMs ) {
    public static final String PREFIX = "wikantik.bundle.reranker.";
    public RerankerConfig {
        if ( model == null || model.isBlank() ) model = "gemma4:e4b";
        if ( baseUrl == null || baseUrl.isBlank() ) baseUrl = "http://inference.jakefear.com:11434";
        if ( timeoutMs <= 0 ) timeoutMs = 30_000L;
    }
}
