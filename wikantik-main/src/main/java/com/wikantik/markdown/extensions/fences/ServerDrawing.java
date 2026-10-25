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
package com.wikantik.markdown.extensions.fences;

/**
 * What a {@link ServerFenceDrawer} produced for one fence: both theme variants of the drawing, or a refusal reason
 * (a lowercase-hyphen code such as {@code syntax-error} or {@code busy}) with an optional message and source line.
 *
 * @param lightSvg         the light-theme SVG, or {@code null} when refused
 * @param darkSvg          the dark-theme SVG, or {@code null} when refused
 * @param reason           the refusal reason, or {@code null} when drawn
 * @param message          a human-readable explanation, or {@code null}
 * @param line             the 1-based source line the refusal points at; 0 when unknown
 * @param transientFailure true when the refusal may not recur (busy, page budget, unexpected failure)
 */
public record ServerDrawing( String lightSvg, String darkSvg, String reason, String message, int line,
                             boolean transientFailure ) {

    /** @return a drawing carrying both variants */
    public static ServerDrawing drawn( final String lightSvg, final String darkSvg ) {
        return new ServerDrawing( lightSvg, darkSvg, null, null, 0, false );
    }

    /** @return a refusal; {@code line} 0 means unknown, {@code message} may be null */
    public static ServerDrawing refused( final String reason, final String message, final int line,
                                         final boolean transientFailure ) {
        return new ServerDrawing( null, null, reason, message, line, transientFailure );
    }

    /** @return true when both variants are present */
    public boolean isDrawn() {
        return lightSvg != null && darkSvg != null;
    }
}
