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
package com.wikantik.api.content;

import com.wikantik.api.exceptions.FilterException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown by the save pipeline when body-content validation finds at least one
 * {@link com.wikantik.api.frontmatter.schema.Severity#ERROR}-severity
 * {@link ContentViolation}. Extends {@link FilterException} so it flows through the
 * existing {@code preSave} filter chain; {@code PageResource} catches it specifically
 * and emits an HTTP 422 carrying the structured {@link #violations()}.
 */
public class ContentValidationException extends FilterException {

    private final transient List< ContentViolation > violations;

    public ContentValidationException( final List< ContentViolation > violations ) {
        super( buildMessage( violations ) );
        this.violations = List.copyOf( violations );
    }

    /** The error-severity violations that blocked the write. */
    public List< ContentViolation > violations() {
        return violations;
    }

    private static String buildMessage( final List< ContentViolation > violations ) {
        return "Content validation failed (" + violations.size() + " error"
                + ( violations.size() == 1 ? "" : "s" ) + "): "
                + violations.stream().map( ContentViolation::message )
                            .collect( Collectors.joining( "; " ) );
    }
}
