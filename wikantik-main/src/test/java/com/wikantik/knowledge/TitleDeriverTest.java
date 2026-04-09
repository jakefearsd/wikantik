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
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TitleDeriverTest {

    @Test
    void camelCase() {
        assertEquals( "My Page Name", TitleDeriver.derive( "MyPageName" ) );
    }

    @Test
    void underscoreDelimited() {
        assertEquals( "My Page Name", TitleDeriver.derive( "my_page_name" ) );
    }

    @Test
    void mixedCamelAndUnderscore() {
        assertEquals( "My Page Name", TitleDeriver.derive( "My_PageName" ) );
    }

    @Test
    void singleWord() {
        assertEquals( "Hello", TitleDeriver.derive( "Hello" ) );
    }

    @Test
    void alreadySpaced() {
        assertEquals( "Already Spaced", TitleDeriver.derive( "Already Spaced" ) );
    }

    @Test
    void acronymHandling() {
        assertEquals( "HTTP Server Config", TitleDeriver.derive( "HTTPServerConfig" ) );
    }

    @Test
    void hyphenDelimited() {
        assertEquals( "Some Page Name", TitleDeriver.derive( "some-page-name" ) );
    }
}
