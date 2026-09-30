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

import com.wikantik.api.pagegraph.PageDescriptor;

/**
 * The outcome of resolving an {@link ExportSelection} against an {@link ExportCatalog}:
 * the final page set (sorted by slug), plus counts useful for a preview UI —
 * how many pages matched the filters before hop expansion, how many hop expansion
 * added, and how many were subsequently dropped because the caller may not view them.
 */
public record ResolvedSelection( List< PageDescriptor > pages, int seedCount, int hopAdded, int aclDropped ) {}
