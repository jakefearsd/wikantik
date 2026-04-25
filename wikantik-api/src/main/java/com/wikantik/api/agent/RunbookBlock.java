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
 * Parsed and validated {@code runbook:} frontmatter block. Constructed only
 * by {@code FrontmatterRunbookValidator} — the field-level rules in the
 * design doc are enforced there, not in this record's compact constructor.
 *
 * <p>Field names use the snake_case wire form so default Gson serialisation
 * produces the on-the-wire shape ({@code when_to_use}, {@code related_tools},
 * etc.) without a per-instance naming policy. Phase 2's
 * {@code ForAgentProjection.runbook} field is typed {@code Object} precisely
 * so this type can land here in Phase 3 without an API change.</p>
 */
@SuppressWarnings( "checkstyle:MemberName" )
public record RunbookBlock(
        List< String > when_to_use,
        List< String > inputs,
        List< String > steps,
        List< String > pitfalls,
        List< String > related_tools,
        List< String > references
) {
    public RunbookBlock {
        when_to_use   = when_to_use   == null ? List.of() : List.copyOf( when_to_use );
        inputs        = inputs        == null ? List.of() : List.copyOf( inputs );
        steps         = steps         == null ? List.of() : List.copyOf( steps );
        pitfalls      = pitfalls      == null ? List.of() : List.copyOf( pitfalls );
        related_tools = related_tools == null ? List.of() : List.copyOf( related_tools );
        references    = references    == null ? List.of() : List.copyOf( references );
    }
}
