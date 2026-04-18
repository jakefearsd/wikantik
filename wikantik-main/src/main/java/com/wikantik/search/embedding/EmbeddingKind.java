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
package com.wikantik.search.embedding;

/**
 * Distinguishes the two roles a text can play in an asymmetric retrieval model.
 * Several embedding models (nomic, Qwen3) require a different prefix or
 * instruction depending on whether the text is a corpus document or a user query
 * — mixing the two silently destroys recall. Callers must declare which one they
 * are embedding.
 */
public enum EmbeddingKind {
    QUERY,
    DOCUMENT
}
