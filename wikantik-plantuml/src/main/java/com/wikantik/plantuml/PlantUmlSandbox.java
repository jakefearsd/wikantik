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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forces PlantUML into its SANDBOX security profile before any PlantUML state is initialised. PlantUML
 * reads {@code PLANTUML_SECURITY_PROFILE} from System properties before the environment and caches the
 * profile on first use, so the property has to be set first. If the sandbox cannot be confirmed, callers
 * must treat PlantUML as disabled (fail closed).
 */
public final class PlantUmlSandbox {

    private static final Logger LOG = LogManager.getLogger( PlantUmlSandbox.class );
    private static final String PROFILE_PROPERTY = "PLANTUML_SECURITY_PROFILE";

    private PlantUmlSandbox() {
    }

    /**
     * Idempotently sets the profile property and reports whether PlantUML is actually in SANDBOX.
     *
     * @return true only when {@code SecurityUtils.getSecurityProfile()} is {@link SecurityProfile#SANDBOX}
     */
    public static boolean ensure() {
        try {
            System.setProperty( PROFILE_PROPERTY, SecurityProfile.SANDBOX.name() );
            final SecurityProfile profile = SecurityUtils.getSecurityProfile();
            if( profile != SecurityProfile.SANDBOX ) {
                LOG.error( "PlantUML security profile is {} rather than SANDBOX; PlantUML rendering is disabled", profile );
                return false;
            }
            return true;
        } catch( final RuntimeException | LinkageError e ) {
            LOG.error( "Could not confirm the PlantUML sandbox; PlantUML rendering is disabled", e );
            return false;
        }
    }
}
