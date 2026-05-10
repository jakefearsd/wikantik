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
package com.wikantik.api.agent;

import java.util.List;

/**
 * Derived agent hints for the {@code /for-agent} projection. Computed in code
 * by {@code AgentHintsDeriver} from existing graph/metadata signals; never
 * authored in frontmatter (rejected as author burden — see
 * {@code docs/superpowers/specs/2026-05-10-derived-agent-hints-design.md}).
 *
 * <p>Snake_case Java field names so default Gson serialisation matches the
 * wire form (mirrors {@link RunbookBlock} convention).</p>
 */
@SuppressWarnings( "checkstyle:MemberName" )
public record AgentHintsBlock(
        List< String > prefer_tools,
        List< PreferredPage > prefer_pages
) {
    public AgentHintsBlock {
        prefer_tools = prefer_tools == null ? List.of() : List.copyOf( prefer_tools );
        prefer_pages = prefer_pages == null ? List.of() : List.copyOf( prefer_pages );
    }

    public static AgentHintsBlock empty() {
        return new AgentHintsBlock( List.of(), List.of() );
    }
}
