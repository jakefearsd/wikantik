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
package com.wikantik.api.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Materialises a classpath resource that is not a plain file (for example one packaged inside a jar) as
 * a real file, for consumers such as the security policy reader that can only open a {@link File}.
 *
 * <p>The copy is made at most once per resource name and reused by later callers, so repeated lookups
 * (the security verifier re-resolves the policy on every run) do not accumulate temp files. Its lifetime
 * is deliberately the lifetime of the JVM: the policy implementation keeps the {@link File} and re-reads
 * it on permission checks, so deleting it after the first read would break authorization. It is removed
 * by a single {@code deleteOnExit()} registration per name.</p>
 */
final class ConfigFileCopies {

    private static final Logger LOG = LogManager.getLogger( ConfigFileCopies.class );
    private static final Map< String, File > COPIES = new HashMap<>();

    private ConfigFileCopies() {
    }

    /**
     * Returns a file holding the content of {@code resource}, creating it on first use.
     *
     * @param name     the config file name, used for the cache key and the temp file prefix
     * @param resource the classpath resource to copy
     * @return the copy, or {@code null} if it could not be created (already logged)
     */
    static synchronized File tempCopy( final String name, final URL resource ) {
        final File existing = COPIES.get( name );
        if ( existing != null && existing.isFile() ) {
            return existing;
        }
        final File tmpFile;
        try {
            tmpFile = File.createTempFile( "temp." + name, "" );
        } catch ( final IOException e ) {
            LOG.error( "unable to create a temp file to load {} onto the policy", name, e );
            return null;
        }
        try ( InputStream is = resource.openStream() ) {
            Files.copy( is, tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING );
        } catch ( final IOException e ) {
            LOG.error( "failed to load security policy from file {}, stacktrace follows", name, e );
            if ( !tmpFile.delete() ) {
                LOG.warn( "could not delete incomplete temp copy {}", tmpFile );
            }
            return null;
        }
        tmpFile.deleteOnExit();
        COPIES.put( name, tmpFile );
        return tmpFile;
    }
}
