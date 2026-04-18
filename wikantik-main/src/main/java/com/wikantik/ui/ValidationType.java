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
package com.wikantik.ui;

import java.util.regex.Pattern;

/**
 * Self-contained validation strategies for {@link InputValidator}. Each constant carries
 * its regex, match semantics, i18n message key and the extra argument (if any) passed to
 * the message — eliminating the switch that previously lived in {@code validate()}.
 */
public enum ValidationType {

    STANDARD( InputValidator.UNSAFE_PATTERN, MatchMode.REJECT_ON_FIND,
              "validate.unsafechars", "'\"<>;&[]#\\@{}%$" ),

    EMAIL( InputValidator.EMAIL_PATTERN, MatchMode.REQUIRE_FULL_MATCH,
           "validate.invalidemail", null ),

    ID( InputValidator.ID_PATTERN, MatchMode.REJECT_ON_FIND,
        "validate.unsafechars", "'\"<>;&{}" );

    private final Pattern pattern;
    private final MatchMode mode;
    private final String messageKey;
    private final String unsafeCharsArg;

    ValidationType( final Pattern pattern, final MatchMode mode,
                    final String messageKey, final String unsafeCharsArg ) {
        this.pattern = pattern;
        this.mode = mode;
        this.messageKey = messageKey;
        this.unsafeCharsArg = unsafeCharsArg;
    }

    boolean isValid( final String input ) {
        return mode.matches( pattern, input );
    }

    String messageKey() {
        return messageKey;
    }

    Object[] messageArgs( final String label ) {
        return unsafeCharsArg == null
                ? new Object[]{ label }
                : new Object[]{ label, unsafeCharsArg };
    }

    private enum MatchMode {
        REJECT_ON_FIND {
            @Override boolean matches( final Pattern p, final String input ) {
                return !p.matcher( input ).find();
            }
        },
        REQUIRE_FULL_MATCH {
            @Override boolean matches( final Pattern p, final String input ) {
                return p.matcher( input ).matches();
            }
        };
        abstract boolean matches( Pattern p, String input );
    }
}
