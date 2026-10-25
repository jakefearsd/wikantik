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
package com.wikantik.parser;

import java.util.function.UnaryOperator;

/**
 * Render nesting on the current thread, so that server-fence drawings are spliced in only by the outermost
 * {@code RenderingManager.textToHTML(Context, String)}. Every render entry of {@code DefaultRenderingManager} and
 * every Markdown parse counts itself: a {@code textToHTML} reached from inside
 * one of them (InsertPage, an embed body, a plugin body, a plugin run by a metadata or reference parse) returns
 * placeholders, because its HTML is going into an enclosing render whose sanitize pass would strip a drawing, or
 * nowhere at all. Each {@link #enter()} must be paired with a {@link #leave()} in a {@code finally}.
 * <p>It lives in the parser package (a leaf both the parser and the rendering manager already depend on) so that
 * counting a parse adds no parser&rarr;render dependency.</p>
 */
public final class RenderDepth {

    private static final ThreadLocal< int[] > RENDER_DEPTH = ThreadLocal.withInitial( () -> new int[ 1 ] );

    private RenderDepth() {
    }

    /** A body run one level deeper. */
    @FunctionalInterface
    public interface Body< T, E extends Exception > {
        /** @return the body's result */
        T run() throws E;
    }

    /**
     * Runs {@code body} one level deeper.
     * @param body the parse or render
     * @return its result
     * @throws E whatever the body throws
     */
    public static < T, E extends Exception > T nested( final Body< T, E > body ) throws E {
        enter();
        try {
            return body.run();
        } finally {
            leave();
        }
    }

    /**
     * Runs {@code body} one level deeper and, when it was the outermost level, passes its result through
     * {@code finish} (still inside the level, so a render {@code finish} triggers stays nested).
     * @param body the render
     * @param finish applied to the outermost render's result only
     * @return the (finished) result
     */
    public static String outermost( final Body< String, RuntimeException > body, final UnaryOperator< String > finish ) {
        final boolean outer = enter();
        try {
            final String result = body.run();
            return outer ? finish.apply( result ) : result;
        } finally {
            leave();
        }
    }

    /** Enters one level; @return true when nothing else was being parsed or rendered on this thread */
    public static boolean enter() {
        return RENDER_DEPTH.get()[ 0 ]++ == 0;
    }

    /** Leaves one level; the thread-local is cleared once the outermost level is left. */
    public static void leave() {
        final int[] depth = RENDER_DEPTH.get();
        if( --depth[ 0 ] <= 0 ) {
            RENDER_DEPTH.remove();
        }
    }
}
