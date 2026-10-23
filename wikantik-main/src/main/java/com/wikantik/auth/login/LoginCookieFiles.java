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
package com.wikantik.auth.login;

import com.wikantik.api.core.Engine;
import com.wikantik.util.FileUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The remember-me cookie store of {@link CookieAuthenticationLoginModule}: one file per issued cookie under
 * {@code <workDir>/logincookies}, named by the cookie's bearer token and holding the login it was issued to.
 */
public final class LoginCookieFiles {

    private static final Logger LOG = LogManager.getLogger( LoginCookieFiles.class );

    private LoginCookieFiles() { }

    /** Reads the login a remember-me cookie file was issued to (the file's whole content). */
    static String readOwner( final File cookieFile ) throws IOException {
        try( Reader in = new BufferedReader( new InputStreamReader( Files.newInputStream( cookieFile.toPath() ), StandardCharsets.UTF_8 ) ) ) {
            return FileUtil.readContents( in );
        }
    }

    /**
     * Deletes every remember-me cookie file issued to {@code loginName}, so the cookies a browser still
     * holds stop authenticating. Called when the account is deleted: the login module accepts a cookie
     * whose login has no profile, so a surviving file would keep re-authenticating the deleted user —
     * and would log its holder into any account later created under the same login. A file that
     * cannot be read is skipped (it cannot authenticate either).
     *
     * @param engine    the wiki engine, whose work directory holds the cookie files
     * @param loginName the login whose cookies to revoke; {@code null} revokes nothing
     * @return how many cookie files were removed
     * @throws IOException if the cookie directory cannot be listed or a matching file cannot be deleted
     */
    public static int revokeFor( final Engine engine, final String loginName ) throws IOException {
        final String workDir = engine.getWorkDir();
        if( workDir == null || loginName == null ) {
            return 0;
        }
        final File cookieDir = new File( workDir, CookieAuthenticationLoginModule.COOKIE_DIR );
        if( !cookieDir.isDirectory() ) {
            return 0;
        }
        final File[] files = cookieDir.listFiles();
        if( files == null ) {
            throw new IOException( "Could not list the remember-me cookie directory " + cookieDir.getAbsolutePath() );
        }
        int removed = 0;
        for( final File cookieFile : files ) {
            if( cookieFile.isFile() && loginName.equals( ownerOrNull( cookieFile ) ) ) {
                if( !cookieFile.delete() ) {
                    // The file name is the bearer token: report only the directory.
                    throw new IOException( "Could not delete a remember-me cookie file of '" + loginName + "' in "
                            + cookieDir.getAbsolutePath() );
                }
                removed++;
            }
        }
        return removed;
    }

    private static String ownerOrNull( final File cookieFile ) {
        try {
            return readOwner( cookieFile );
        } catch( final IOException e ) {
            LOG.warn( "Could not read a remember-me cookie file in {} while revoking cookies; skipping it: {}",
                    cookieFile.getParent(), e.getMessage() );
            return null;
        }
    }
}
