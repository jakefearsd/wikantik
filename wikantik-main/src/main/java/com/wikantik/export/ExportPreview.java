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
package com.wikantik.export;

import java.util.List;

/**
 * Cheap, no-conversion-run preview of what an {@link ExportSelection} would produce: how many
 * pages and attachments, a rough byte estimate, how many outbound links would leave the export
 * set unresolved, whether the selection exceeds the configured cap, and a sample of slugs for a
 * picker UI.
 */
public record ExportPreview( int pages, int attachments, long estimatedBytes, int unresolvedLinks,
                             int cap, boolean overCap, List< String > sample ) {}
