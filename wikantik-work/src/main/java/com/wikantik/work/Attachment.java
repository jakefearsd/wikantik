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

import java.time.Instant;

/**
 * One file attached to an item. {@code removedAt} is set once the attachment was removed (its bytes are gone, the row
 * and its events stay); every read through the store returns live attachments only.
 */
public record Attachment( long id, long itemId, Long commentId, String filename, String contentType, long sizeBytes,
                          String sha256, String uploadedBy, Long uploadedByAgentId, Instant uploadedAt,
                          Instant removedAt ) {
}
