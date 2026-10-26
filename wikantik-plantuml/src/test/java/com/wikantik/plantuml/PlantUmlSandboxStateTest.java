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

import net.sourceforge.plantuml.security.SecurityProfile;
import net.sourceforge.plantuml.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sandbox's end state. PlantUML caches its profile in a private static, so the "profile is not SANDBOX" path
 * is reached by planting a different cached profile reflectively and always restoring it afterwards.
 */
@Isolated
class PlantUmlSandboxStateTest {

    private Field current;

    @BeforeEach
    void locateProfileCache() throws Exception {
        current = SecurityUtils.class.getDeclaredField( "current" );
        current.setAccessible( true );
    }

    @AfterEach
    void restoreSandbox() throws Exception {
        current.set( null, null ); // re-initialised from the PLANTUML_SECURITY_PROFILE property on next use
        System.setProperty( "PLANTUML_SECURITY_PROFILE", SecurityProfile.SANDBOX.name() );
        PlantUmlSandbox.ensure();
    }

    @Test
    void ensureLeavesSandboxProfileAndNoGraphviz() {
        System.setProperty( "GRAPHVIZ_DOT", "/usr/bin/dot" );
        System.setProperty( "PLANTUML_SECURITY_PROFILE", "UNSECURE" );

        assertTrue( PlantUmlSandbox.ensure() );

        assertEquals( "SANDBOX", System.getProperty( "PLANTUML_SECURITY_PROFILE" ), "an operator's value must be overridden" );
        assertEquals( PlantUmlSandbox.NO_GRAPHVIZ, System.getProperty( "GRAPHVIZ_DOT" ), "an operator's dot must be overridden" );
        assertFalse( new java.io.File( System.getProperty( "GRAPHVIZ_DOT" ) ).exists() );
        assertEquals( SecurityProfile.SANDBOX, SecurityUtils.getSecurityProfile() );
    }

    @Test
    void ensureIsIdempotent() {
        assertTrue( PlantUmlSandbox.ensure() );
        assertTrue( PlantUmlSandbox.ensure() );
        assertEquals( PlantUmlSandbox.NO_GRAPHVIZ, System.getProperty( "GRAPHVIZ_DOT" ) );
    }

    @Test
    void ensureFailsClosedWhenPlantUmlAlreadyCachedAWeakerProfile() throws Exception {
        current.set( null, SecurityProfile.INSECURE );

        assertFalse( PlantUmlSandbox.ensure(), "a cached non-SANDBOX profile cannot be undone, so PlantUML must be reported disabled" );
        assertFalse( PlantUmlSandbox.ensure(), "and it stays disabled on every later call" );
        // the properties are still forced, so a later JVM-wide re-init would land in the sandbox
        assertEquals( PlantUmlSandbox.NO_GRAPHVIZ, System.getProperty( "GRAPHVIZ_DOT" ) );
    }

    @Test
    void engineRefusesEveryRenderWhenSandboxCannotBeConfirmed() throws Exception {
        current.set( null, SecurityProfile.LEGACY );

        final RenderOutcome first = PlantUmlEngine.render( "class A" );
        final RenderOutcome second = PlantUmlEngine.render( "class A" );

        assertFalse( first.isDrawn() );
        assertEquals( "render-failed", first.reason() );
        assertTrue( first.transientFailure(), "must not be cached: the sandbox may be confirmed after a restart" );
        assertEquals( "render-failed", second.reason() );
    }

    @Test
    void sandboxGuardIsOnlyLoggedOnce() throws Exception {
        final Field flag = PlantUmlSandbox.class.getDeclaredField( "ERROR_LOGGED" );
        flag.setAccessible( true );
        final AtomicBoolean logged = (AtomicBoolean) flag.get( null );
        current.set( null, SecurityProfile.INSECURE );
        PlantUmlSandbox.ensure();
        assertTrue( logged.get() );
        PlantUmlSandbox.ensure();
        assertTrue( logged.get() );
    }

    @Test
    void ensureFailsClosedWhenPlantUmlThrowsWhileReportingItsProfile() {
        try( MockedStatic< SecurityUtils > utils = Mockito.mockStatic( SecurityUtils.class ) ) {
            utils.when( SecurityUtils::getSecurityProfile ).thenThrow( new IllegalStateException( "synthetic" ) );
            assertFalse( PlantUmlSandbox.ensure() );
        }
        try( MockedStatic< SecurityUtils > utils = Mockito.mockStatic( SecurityUtils.class ) ) {
            utils.when( SecurityUtils::getSecurityProfile ).thenThrow( new NoClassDefFoundError( "synthetic" ) );
            assertFalse( PlantUmlSandbox.ensure() );
        }
        assertEquals( PlantUmlSandbox.NO_GRAPHVIZ, System.getProperty( "GRAPHVIZ_DOT" ) );
    }
}
