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

import java.util.List;

/**
 * An item's live attachments with what a client needs to offer an upload: whether this caller may attach right now,
 * the configured per-file and per-item limits, and the bytes the live attachments already use of the item limit.
 */
public record AttachmentListing( List< Attachment > attachments, boolean mayAttach, long maxFileBytes,
                                 long maxItemBytes, long usedBytes ) {
}
