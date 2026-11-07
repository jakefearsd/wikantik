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
package com.wikantik.its;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Whether the browser suite runs against a server with collaborative editing ON ({@code bin/run-tests.sh --collab}
 * passes {@code -Dit.collab=true}), and the guard that makes a silent fall-back to the single-author editor a test
 * failure instead of a quietly different test.
 */
public final class CollabMode {

    /** The system property {@code bin/run-tests.sh --collab} sets for the failsafe JVM. */
    public static final String PROPERTY = "it.collab";

    private static final Duration READY_WAIT = Duration.ofSeconds( 10 );

    private CollabMode() {}

    /** @return {@code true} when the suite was started in collaborative mode. */
    public static boolean enabled() {
        return Boolean.parseBoolean( System.getProperty( PROPERTY, "false" ) );
    }

    /**
     * In collab mode, waits for the editor's connection indicator to report {@code live}; fails when the editor
     * never shows one ("fell back to single-author mode") or never goes live. In single-author mode returns at once.
     */
    public static void awaitEditorReady() {
        awaitReady( enabled(), CollabMode::probeStatus, READY_WAIT );
    }

    /**
     * The guard logic over a DOM probe, for unit testing.
     *
     * @param collab whether collab mode is on.
     * @param status reads the {@code data-status} of {@code collab-status}, or {@code null} when the element is absent.
     * @param timeout how long to wait for {@code live}.
     */
    static void awaitReady( final boolean collab, final Supplier< String > status, final Duration timeout ) {
        if ( !collab ) {
            return;
        }
        final long deadline = System.nanoTime() + timeout.toNanos();
        String last = status.get();
        while ( !"live".equals( last ) && System.nanoTime() < deadline ) {
            Selenide.sleep( 100 );
            last = status.get();
        }
        if ( "live".equals( last ) ) {
            return;
        }
        if ( last == null ) {
            throw new AssertionError( "editor fell back to single-author mode: no [data-testid=collab-status] within "
                + timeout + " while collaborative editing is on" );
        }
        throw new AssertionError( "collab editor never went live within " + timeout + "; last status: " + last );
    }

    private static String probeStatus() {
        final List< WebElement > found = WebDriverRunner.getWebDriver()
            .findElements( By.cssSelector( "[data-testid=collab-status]" ) );
        return found.isEmpty() ? null : found.get( 0 ).getAttribute( "data-status" );
    }
}
