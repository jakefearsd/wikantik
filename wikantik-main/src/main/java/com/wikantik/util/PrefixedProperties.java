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
package com.wikantik.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Reads typed values from a {@link Properties} bundle under a fixed key prefix,
 * falling back to a supplied default when a key is absent, blank, or malformed.
 *
 * <p>A malformed numeric/boolean value is logged at INFO (with the full key) and
 * coerced to the default rather than throwing — a single config typo degrades to
 * the default instead of failing startup. A {@code null} {@code Properties} makes
 * every lookup return its default.
 *
 * <p>Extracted from the byte-identical per-config parsing helpers that were
 * previously duplicated across {@code BundleDecompositionConfig} and
 * {@code EntityExtractorConfig} (and reusable by any future prefixed-config record).
 * Distinct from {@link PropertyReader}, which loads whole web-app property bundles.
 */
public final class PrefixedProperties {

    private static final Logger LOG = LogManager.getLogger( PrefixedProperties.class );

    private final Properties props;
    private final String prefix;

    /**
     * @param props  the property bundle to read from; {@code null} is allowed and
     *               makes every lookup return its default
     * @param prefix the common key prefix prepended to every {@code suffix}
     */
    public PrefixedProperties( final Properties props, final String prefix ) {
        this.props = props;
        this.prefix = prefix;
    }

    public String getString( final String suffix, final String def ) {
        final String v = props == null ? null : props.getProperty( prefix + suffix );
        return v == null || v.isBlank() ? def : v.trim();
    }

    public long getLong( final String suffix, final long def ) {
        final String v = props == null ? null : props.getProperty( prefix + suffix );
        if( v == null || v.isBlank() ) {
            return def;
        }
        try {
            return Long.parseLong( v.trim() );
        } catch( final NumberFormatException e ) {
            LOG.info( "Ignoring non-numeric value '{}' for property {}{} — using default {}: {}",
                v, prefix, suffix, def, e.getMessage() );
            return def;
        }
    }

    public int getInt( final String suffix, final int def ) {
        return (int) getLong( suffix, def );
    }

    public double getDouble( final String suffix, final double def ) {
        final String v = props == null ? null : props.getProperty( prefix + suffix );
        if( v == null || v.isBlank() ) {
            return def;
        }
        try {
            return Double.parseDouble( v.trim() );
        } catch( final NumberFormatException e ) {
            LOG.info( "Ignoring non-numeric value '{}' for property {}{} — using default {}: {}",
                v, prefix, suffix, def, e.getMessage() );
            return def;
        }
    }

    public boolean getBoolean( final String suffix, final boolean def ) {
        final String v = props == null ? null : props.getProperty( prefix + suffix );
        if( v == null || v.isBlank() ) {
            return def;
        }
        final String trimmed = v.trim();
        if( "true".equalsIgnoreCase( trimmed ) || "1".equals( trimmed ) || "yes".equalsIgnoreCase( trimmed ) ) {
            return true;
        }
        if( "false".equalsIgnoreCase( trimmed ) || "0".equals( trimmed ) || "no".equalsIgnoreCase( trimmed ) ) {
            return false;
        }
        LOG.info( "Ignoring non-boolean value '{}' for property {}{} — using default {}",
            v, prefix, suffix, def );
        return def;
    }
}
