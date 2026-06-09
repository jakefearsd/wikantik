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
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * The Knowledge-Graph slice for a single wiki page: all entities mentioned on
 * that page, plus the edges among them (intra-page edges only — edges where at
 * least one endpoint is off-page are excluded).
 *
 * <p>Both lists are unmodifiable. An unknown page or a page with no entity
 * mentions yields empty lists, never {@code null}.</p>
 *
 * @param entities all {@link KgNode}s mentioned on the page
 * @param edges    intra-page {@link KgEdgeView}s with endpoint names resolved
 */
public record PageKnowledgeSlice(
    List< KgNode > entities,
    List< KgEdgeView > edges
) {
    public PageKnowledgeSlice {
        entities = entities == null ? List.of() : List.copyOf( entities );
        edges    = edges    == null ? List.of() : List.copyOf( edges    );
    }
}
