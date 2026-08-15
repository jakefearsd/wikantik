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
package com.wikantik.i18n;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import org.apache.commons.lang3.StringUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks to ensure all i18n keys have values is all languages
 *
 * see also  https://wiki.wikantik.com/Wiki.jsp?page=HowToI18n
 * and  https://wikantik.com/development/i18n.html
 */
public class InternationalizationManagerTest {

    InternationalizationManager i18n = new DefaultInternationalizationManager(null);

    @BeforeEach
    public void setUp() throws Exception {
        // enforce english locale as the default one. Otherwise, if your default locale is one
        // of the given translations, ResourceBundle.getBundle(String, Locale.ENGLISH) will 
        // return the bundle of your locale, rather than returning the default -english- one
        Locale.setDefault(Locale.ENGLISH);
    }

    @Test
    public void testGetFromCoreWithArgs() {
        final String str = i18n.get(InternationalizationManager.CORE_BUNDLE,
                Locale.ENGLISH,
                "security.error.cannot.rename",
                "Test User");
        Assertions.assertEquals("Cannot rename: the login name 'Test User' is already taken.", str);
    }

    @Test
    void notificationKeysLiveInCoreBundle() {
        final ResourceBundle rb = ResourceBundle.getBundle( InternationalizationManager.CORE_BUNDLE, Locale.ENGLISH );
        assertTrue( rb.containsKey( "notification.createUserProfile.accept.subject" ) );
        assertTrue( rb.containsKey( "notification.createUserProfile.accept.content" ) );
        assertTrue( rb.containsKey( "notification.createUserProfile.admin.subject" ) );
        assertTrue( rb.containsKey( "notification.createUserProfile.admin.content" ) );
    }

    @Test
    public void scanForMissingI18NStrings() throws IOException {
        Properties props = loadProperties(new File("src/main/resources/CoreResources.properties"));
        File[] propFiles = new File("src/main/resources").listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                if (pathname.isDirectory()) {
                    return false;
                }
                if (!pathname.getName().endsWith(".properties")) {
                    return false;
                }
                if (pathname.getName().equals("CoreResources.properties")) {
                    return false;
                }
                return true;

            }
        });
        List<String> missingMessages = new ArrayList<>();

        for (File propFile : propFiles) {
            Properties target = loadProperties(propFile);
            for (Object key : props.keySet()) {
                if (!target.containsKey(key)) {
                    missingMessages.add(propFile.getName() + " is missing key '" + key + "'");
                }
            }
        }
        Assertions.assertTrue(missingMessages.isEmpty(), StringUtils.join(missingMessages, "\n"));
    }
    
    @Test
    public void scanForMissingI18NStrings3() throws IOException {
        Properties props = loadProperties(new File("src/main/resources/plugin/PluginResources.properties"));
        File[] propFiles = new File("src/main/resources/plugin").listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                if (pathname.isDirectory()) {
                    return false;
                }
                if (!pathname.getName().endsWith(".properties")) {
                    return false;
                }
                if (pathname.getName().equals("PluginResources.properties")) {
                    return false;
                }
                return true;

            }
        });
        List<String> missingMessages = new ArrayList<>();

        for (File propFile : propFiles) {
            Properties target = loadProperties(propFile);
            for (Object key : props.keySet()) {
                if (!target.containsKey(key)) {
                    missingMessages.add(propFile.getName() + " is missing key '" + key + "'");
                }
            }
        }
        Assertions.assertTrue(missingMessages.isEmpty(), StringUtils.join(missingMessages, "\n"));
    }
    

    private Properties loadProperties(File propFile) throws IOException {
        Properties p = new Properties();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(propFile);
            p.load(fis);
        } finally {
            if (fis != null) {
                fis.close();
            }
        }
        return p;
    }
    
}
