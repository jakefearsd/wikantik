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
package com.wikantik.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.DataHolder;
import com.wikantik.parser.markdown.FlexmarkParsers;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * F-1 (#129): every flexmark parser is built by {@link FlexmarkParsers}, whose heading factory does not rebuild a
 * paragraph's text on every line. A parser built with flexmark's own {@link Parser#builder()} parses a long paragraph of
 * lines that start with a non-letter in quadratic time, which any editor can write into a page.
 */
@AnalyzeClasses(
    packages = "com.wikantik",
    importOptions = { ImportOption.DoNotIncludeTests.class }
)
class FlexmarkParserArchTest {

    @ArchTest
    static final ArchRule parsers_are_built_by_flexmark_parsers =
        noClasses()
            .that().doNotBelongToAnyOf( FlexmarkParsers.class )
            .should().callMethod( Parser.class, "builder" )
            .orShould().callMethod( Parser.class, "builder", DataHolder.class )
            .as( "F-1: build flexmark parsers with com.wikantik.parser.markdown.FlexmarkParsers.builder(..), "
               + "never Parser.builder(..) (#129: flexmark's heading factory is quadratic in a paragraph's lines)" );
}
