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
package com.wikantik.api.content;

import com.wikantik.api.frontmatter.schema.Severity;

/**
 * A single body-content validation finding with a body-relative {@link Location}.
 *
 * <p>Analogous to {@link com.wikantik.api.frontmatter.schema.FieldViolation} for
 * frontmatter, but addresses content-body violations such as malformed LaTeX.
 *
 * @param locus    Category tag identifying the subsystem that produced the violation
 *                 (e.g. {@code "math"}).
 * @param severity {@link Severity#ERROR} blocks the save; {@link Severity#WARNING} is
 *                 advisory (the save proceeds, the violation is surfaced on the response).
 * @param code     A stable machine code, e.g. {@code math.display.notIsolated}.
 * @param message  A human/agent-readable explanation.
 * @param location Body-relative source location (line, column, offsets, excerpt, caret).
 */
public record ContentViolation(
        String locus,
        Severity severity,
        String code,
        String message,
        Location location
) {}
