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
package com.wikantik.markdown.extensions.math;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The shared inline-math case table (Pandoc {@code tex_math_dollars}). */
class InlineMathRuleTest {

    static Stream< Arguments > cases() {
        return Stream.of(
                Arguments.of( "$x$", List.of( "x" ) ),
                Arguments.of( "$x^2 + y$", List.of( "x^2 + y" ) ),
                Arguments.of( "costs $5 and $10", List.of() ),
                Arguments.of( "$20,000 and $30,000", List.of() ),
                Arguments.of( "$5 and 10$", List.of( "5 and 10" ) ),
                Arguments.of( "$ x$", List.of() ),
                Arguments.of( "$x $", List.of() ),
                Arguments.of( "$x$5", List.of() ),
                Arguments.of( "$x$5 and $y$", List.of( "y" ) ),
                Arguments.of( "$x$, then", List.of( "x" ) ),
                Arguments.of( "$c_o = \\$1.00$", List.of( "c_o = \\$1.00" ) ),
                Arguments.of( "$\\tau = \\$1.50 / (\\$1.50 + \\$1.00) = 0.60$",
                              List.of( "\\tau = \\$1.50 / (\\$1.50 + \\$1.00) = 0.60" ) ),
                Arguments.of( "\\$5 and $x$", List.of( "x" ) ),
                Arguments.of( "$$x$$", List.of() ),
                Arguments.of( "a $b", List.of() ),
                Arguments.of( "$a$$b$", List.of( "a", "b" ) ),
                Arguments.of( "$\\$$", List.of( "\\$" ) ),
                Arguments.of( "$a\\ b$", List.of( "a\\ b" ) ),
                Arguments.of( "$a$b", List.of( "a" ) ),
                Arguments.of( "$\\times$ 6", List.of( "\\times" ) ),
                Arguments.of( "$\\times$6", List.of() ),
                Arguments.of( "$a\nb$", List.of( "a\nb" ) ),
                Arguments.of( "$a\n\nb$", List.of() ),
                Arguments.of( "$a\\", List.of() ),
                Arguments.of( "", List.of() ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "cases" )
    void findsExpectedContents( final String input, final List< String > expected ) {
        final List< String > actual = new ArrayList<>();
        for ( final int[] span : InlineMathRule.findAll( input ) ) {
            actual.add( input.substring( span[ 0 ] + 1, span[ 1 ] - 1 ) );
        }
        Assertions.assertEquals( expected, actual );
    }

    @org.junit.jupiter.api.Test
    void matchEndReturnsExclusiveEndOrMinusOne() {
        Assertions.assertEquals( 3, InlineMathRule.matchEnd( "$x$", 0 ) );
        Assertions.assertEquals( -1, InlineMathRule.matchEnd( "$$x$", 0 ) );
        Assertions.assertEquals( -1, InlineMathRule.matchEnd( "$x", 0 ) );
    }
}
