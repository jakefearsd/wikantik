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
package com.wikantik.plantuml;

/**
 * Result of one render attempt: either a drawing, or a refusal carrying a lowercase-hyphen reason code.
 *
 * @param drawing          the drawing, or {@code null} when refused
 * @param reason           the reason code, or {@code null} when drawn
 * @param message          a human-readable detail for the author, or {@code null}
 * @param line             1-based line in the author's source, or 0 when unknown
 * @param transientFailure true when the refusal must never be cached (the same source may succeed later)
 */
public record RenderOutcome( PlantUmlDrawing drawing, String reason, String message, int line, boolean transientFailure ) {

    /** @return an outcome carrying the drawing */
    public static RenderOutcome drawn( final PlantUmlDrawing d ) {
        return new RenderOutcome( d, null, null, 0, false );
    }

    /** @return a permanent refusal; {@code line} 0 means unknown */
    public static RenderOutcome refused( final String reason, final String message, final int line ) {
        return new RenderOutcome( null, reason, message, line, false );
    }

    /** @return a refusal that must not be cached */
    public static RenderOutcome transientRefusal( final String reason ) {
        return new RenderOutcome( null, reason, null, 0, true );
    }

    /** @return true when a drawing is present */
    public boolean isDrawn() {
        return drawing != null;
    }
}
