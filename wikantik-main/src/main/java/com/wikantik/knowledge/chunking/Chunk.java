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
package com.wikantik.knowledge.chunking;

import java.util.List;

public record Chunk(
    String pageName,
    int chunkIndex,
    List<String> headingPath,
    String text,
    int charCount,
    int tokenCountEstimate,
    String contentHash
) {
    public Chunk {
        if (pageName == null || pageName.isBlank()) {
            throw new IllegalArgumentException("pageName required");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must be >= 0");
        }
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        if (text == null) {
            throw new IllegalArgumentException("text required");
        }
    }
}
