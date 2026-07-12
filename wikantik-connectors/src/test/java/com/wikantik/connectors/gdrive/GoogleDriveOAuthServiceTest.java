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
package com.wikantik.connectors.gdrive;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoogleDriveOAuthServiceTest {
    @Test void authorizationUrlContainsClientRedirectStateScopeAndOfflineConsent() {
        String url = new GoogleDriveOAuthService()
            .authorizationUrl( "CID.apps.googleusercontent.com", "https://wiki/cb", "st8-nonce" );
        assertTrue( url.startsWith( "https://accounts.google.com/o/oauth2/auth" ), url );
        assertTrue( url.contains( "client_id=CID.apps.googleusercontent.com" ), url );
        assertTrue( url.contains( "state=st8-nonce" ), url );
        assertTrue( url.contains( "access_type=offline" ), url );          // guarantees a refresh token
        assertTrue( url.contains( "prompt=consent" ), url );               // forces refresh-token re-issue
        assertTrue( url.contains( "drive.readonly" ), url );               // least-privilege scope
        assertTrue( url.contains( "redirect_uri=" ), url );
    }
}
