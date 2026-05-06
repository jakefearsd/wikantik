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
package com.wikantik.core.subsystem;

import com.wikantik.util.TextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Default {@link WikiProperties} implementation backed by a
 * {@link java.util.Properties} instance. Every {@code get*} method
 * delegates to the corresponding {@code TextUtil.get*Property} static
 * helper so the System-property → env-var → wiki-property precedence is
 * identical to legacy direct reads.
 */
public final class DefaultWikiProperties implements WikiProperties {

    private final Properties delegate;

    public DefaultWikiProperties( final Properties delegate ) {
        this.delegate = Objects.requireNonNull( delegate, "delegate" );
    }

    @Override
    public String get( final String key ) {
        return TextUtil.getStringProperty( delegate, key, null );
    }

    @Override
    public String get( final String key, final String defaultValue ) {
        return TextUtil.getStringProperty( delegate, key, defaultValue );
    }

    @Override
    public int getInt( final String key, final int defaultValue ) {
        return TextUtil.getIntegerProperty( delegate, key, defaultValue );
    }

    @Override
    public long getLong( final String key, final long defaultValue ) {
        // TextUtil doesn't expose a long getter; mirror its semantics inline:
        // System property > env var (with dots → underscores) > properties > default.
        String val = System.getProperty( key, System.getenv( key.replace( ".", "_" ) ) );
        if ( val == null ) val = delegate.getProperty( key );
        if ( val == null || val.isBlank() ) return defaultValue;
        try {
            return Long.parseLong( val.trim() );
        } catch ( final NumberFormatException e ) {
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean( final String key, final boolean defaultValue ) {
        return TextUtil.getBooleanProperty( delegate, key, defaultValue );
    }

    @Override
    public Properties asProperties() {
        return delegate;
    }

    @Override
    public Iterable< String > propertyNames() {
        final List< String > names = new ArrayList<>( delegate.size() );
        for ( final Object name : Collections.list( delegate.propertyNames() ) ) {
            names.add( String.valueOf( name ) );
        }
        return names;
    }
}
