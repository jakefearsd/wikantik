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
 * Draws one server-kind fence language (for example {@code plantuml}) for the {@link ServerFenceResolver}.
 * Implementations run the language's own safety policy again, apply their size and time caps, return scrubbed SVG,
 * and never throw.
 */
public interface ServerFenceDrawer {

    /** @return the lowercase fence language this drawer serves (the {@code data-fence} value) */
    String language();

    /**
     * Draws {@code source}.
     *
     * @param source   the fence source, exactly as signed into its placeholder (LF line endings)
     * @param budgetMs how long the caller may wait for this drawing
     * @return the drawing or a refusal; never {@code null}
     */
    ServerDrawing draw( String source, long budgetMs );

    /** @return the wait budget for a single drawing outside a page render (an editor preview, say) */
    long defaultBudgetMs();
}
