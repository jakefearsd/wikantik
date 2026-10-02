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
package com.wikantik.wikilink;

import com.wikantik.api.core.Context;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Per-render cap and memo for page embeds. One budget lives in the context's (clone-shared) variable map for
 * the duration of a top-level render, so embeds rendered inside embedded bodies draw on the same budget: a
 * render emits at most {@link #MAX_EMBEDS} embed blocks however the embeds fan out, and a repeated
 * (embedding stack, target, section) reuses the first rendering instead of loading and rendering it again.
 * A memo hit is charged the full number of blocks its HTML contains, so reuse can never multiply the output.
 */
public final class EmbedRenderBudget {

    /** Embed blocks one top-level render may emit, nested embeds included. */
    public static final int MAX_EMBEDS = 25;
    /** Context variable holding the active budget; present only while a top-level render is in progress. */
    public static final String ATTR_BUDGET = "com.wikantik.wikilink.EmbedRenderBudget";

    private record Rendered( String html, int cost ) {}

    private final Map< String, Rendered > memo = new HashMap<>();
    private int used;

    private EmbedRenderBudget() {
    }

    /**
     * Enters a render. The outermost render on a context (the one that finds no budget) owns a fresh budget and
     * removes it on close; nested renders join the budget already there and leave it in place on close.
     */
    public static Scope open( final Context context ) {
        if ( current( context ) != null ) {
            return new Scope( context, false );
        }
        context.setVariable( ATTR_BUDGET, new EmbedRenderBudget() );
        return new Scope( context, true );
    }

    /** The budget of the render in progress on {@code context}, or null outside a render. */
    static EmbedRenderBudget current( final Context context ) {
        final Object o = context.getVariable( ATTR_BUDGET );
        return o instanceof EmbedRenderBudget b ? b : null;
    }

    /**
     * The embed's block HTML (memoised under {@code key}), or empty when emitting it would exceed the budget —
     * the caller then degrades the embed to a plain page link.
     */
    Optional< String > render( final String key, final Supplier< String > renderer ) {
        final Rendered hit = memo.get( key );
        if ( hit != null ) {
            if ( used + hit.cost() > MAX_EMBEDS ) {
                return Optional.empty();
            }
            used += hit.cost();
            return Optional.of( hit.html() );
        }
        if ( used >= MAX_EMBEDS ) {
            return Optional.empty();
        }
        used++;
        final int before = used;
        final String html = renderer.get();
        memo.put( key, new Rendered( html, 1 + used - before ) );
        return Optional.of( html );
    }

    /** A render's hold on the budget; closing the owning scope ends the render's budget and memo. */
    public static final class Scope implements AutoCloseable {

        private final Context context;
        private final boolean owner;

        private Scope( final Context context, final boolean owner ) {
            this.context = context;
            this.owner = owner;
        }

        @Override
        public void close() {
            if ( owner ) {
                context.setVariable( ATTR_BUDGET, null );
            }
        }
    }
}
