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
package com.wikantik.api.exceptions;

import com.wikantik.api.frontmatter.schema.FieldViolation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown by the save pipeline when frontmatter fails schema validation with at least one
 * {@code ERROR}-severity {@link FieldViolation}. Extends {@link FilterException} so it flows through
 * the existing {@code preSave} filter chain; {@code PageResource} catches it specifically and emits an
 * HTTP 422 carrying the structured {@link #violations()}.
 */
public class FrontmatterValidationException extends FilterException {

    private final transient List< FieldViolation > violations;

    public FrontmatterValidationException( final List< FieldViolation > violations ) {
        super( buildMessage( violations ) );
        this.violations = List.copyOf( violations );
    }

    public FrontmatterValidationException( final List< FieldViolation > violations, final Throwable cause ) {
        super( buildMessage( violations ), cause );
        this.violations = List.copyOf( violations );
    }

    /** The error-severity violations that blocked the write. */
    public List< FieldViolation > violations() {
        return violations;
    }

    private static String buildMessage( final List< FieldViolation > violations ) {
        return "Frontmatter validation failed: "
                + violations.stream().map( FieldViolation::message ).collect( Collectors.joining( "; " ) );
    }
}
