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
package com.wikantik.providers;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Properties;

/**
 * Resolves the real provider that a caching decorator wraps.
 *
 * <p>{@link CachingProvider} and {@link CachingAttachmentProvider} each carried their own
 * copy of this — read a class name from a property, instantiate it, initialise it, and map
 * the two failure modes — differing only in which property names the class. Both copies
 * were correct and identical in behaviour; this exists so there is one of them rather than
 * two that could drift.
 *
 * <p>Deliberately narrow: it serves the two caching decorators only. The other places that
 * call {@link ClassUtil#buildInstance} look similar but are not the same operation — they
 * differ on the thing that actually matters, which is what happens when instantiation
 * fails. {@code PageSubsystemFactory} treats it as fatal and maps four exception types to
 * {@code WikiException}; {@code DefaultAttachmentManager}, {@code DefaultUserManager} and
 * {@code DefaultGroupManager} degrade instead, logging and continuing without the
 * component; {@code DefaultAuthorizationManager} never calls {@code initialize} at all.
 * Folding those in would mean parameterising the failure policy, which is the complexity
 * this is supposed to remove.
 */
final class ProviderBootstrap {

    private static final Logger LOG = LogManager.getLogger( ProviderBootstrap.class );

    /** Every provider class name resolves against this package when unqualified. */
    private static final String PROVIDER_PACKAGE = "com.wikantik.providers";

    private ProviderBootstrap() {
    }

    /**
     * Reads a provider class name from {@code properties}, instantiates it and initialises
     * it against {@code engine}.
     *
     * @param engine        the engine to initialise the provider against.
     * @param properties    the wiki properties, also passed to the provider's own
     *                      {@code initialize}.
     * @param propertyKey   the property naming the provider class.
     * @param deprecatedKey an older property name still accepted as a fallback, or
     *                      {@code null} if this provider never had one. Dropping it would
     *                      silently break deployments still using the old key.
     * @return the initialised provider.
     * @throws NoRequiredPropertyException if neither property names a class.
     * @throws IllegalArgumentException    if the named class cannot be instantiated; the
     *                                     reflective failure is kept as the cause.
     * @throws IOException                 if the provider's own initialisation fails.
     */
    static < T extends WikiProvider > T resolveAndInitialize( final Engine engine, final Properties properties,
                                                              final String propertyKey, final String deprecatedKey )
            throws NoRequiredPropertyException, IOException {
        final String classname;
        try {
            classname = deprecatedKey == null
                ? TextUtil.getRequiredProperty( properties, propertyKey )
                : TextUtil.getRequiredProperty( properties, propertyKey, deprecatedKey );
        } catch ( final NoSuchElementException e ) {
            throw new NoRequiredPropertyException( e.getMessage(), propertyKey, e );
        }

        try {
            final T provider = ClassUtil.buildInstance( PROVIDER_PACKAGE, classname );
            LOG.debug( "Initializing real provider class {}", provider );
            provider.initialize( engine, properties );
            return provider;
        } catch ( final ReflectiveOperationException e ) {
            // LOG.error justified: a misconfigured provider class blocks engine startup; operators need the stack trace to diagnose.
            LOG.error( "Unable to instantiate provider class {}", classname, e );
            throw new IllegalArgumentException( "illegal provider class", e );
        }
    }
}
