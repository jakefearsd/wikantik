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
package com.wikantik.work.runtime;

import com.wikantik.auth.apikeys.AgentTokenAuth;
import com.wikantik.work.AgentTokens;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The work module mints agent tokens and the wiki's filters recognise them by prefix. The two modules cannot share
 * the constant (the work module depends on neither wikantik-api nor wikantik-main), so this is where they are held
 * equal: a token the filters do not recognise would be sent to the API-key service instead.
 */
class AgentTokenPrefixTest {

    @Test
    void theFiltersRecogniseWhatTheWorkModuleMints() {
        assertEquals( AgentTokens.PREFIX, AgentTokenAuth.PREFIX );
    }
}
