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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class DisplayMathPreProcessorTest {

    @Test
    public void testSingleDisplayMathBlock() {
        final String input = "Some text\n\n$$\n\\int_0^1 f(x)\\,dx\n$$\n\nMore text";
        final String expected = "Some text\n\n```math\n\\int_0^1 f(x)\\,dx\n```\n\nMore text";
        assertEquals( expected, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testMultipleDisplayMathBlocks() {
        final String input = "First\n\n$$\na + b\n$$\n\nMiddle\n\n$$\nc + d\n$$\n\nLast";
        final String expected = "First\n\n```math\na + b\n```\n\nMiddle\n\n```math\nc + d\n```\n\nLast";
        assertEquals( expected, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testInlineDollarSignsNotTransformed() {
        final String input = "The price is $100 and the cost is $200.";
        assertEquals( input, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testInlineDoubleDollarNotTransformed() {
        final String input = "Some text with $$inline$$ math in a paragraph.";
        assertEquals( input, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testEmptyDisplayMathBlock() {
        final String input = "Before\n\n$$\n$$\n\nAfter";
        final String expected = "Before\n\n```math\n```\n\nAfter";
        assertEquals( expected, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testUnclosedDisplayMathAtEndOfDocument() {
        final String input = "Some text\n\n$$\n\\int_0^1 f(x)\\,dx";
        // Unclosed $$ should not be transformed — leave as-is
        assertEquals( input, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testDollarSignDelimitersWithWhitespace() {
        // Leading indent on the opening delimiter is preserved on the emitted fences
        // so that list-nested math blocks retain list-item continuation indentation.
        final String input = "Before\n\n  $$  \nE = mc^2\n  $$  \n\nAfter";
        final String expected = "Before\n\n  ```math\nE = mc^2\n  ```\n\nAfter";
        assertEquals( expected, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testIndentedMathBlockInsideListItem() {
        // A `$$` block nested inside a numbered list item must keep its 4-space
        // continuation indent on both fences so the following indented prose
        // ("Where ...") stays a paragraph continuation of the list item instead
        // of being parsed as an indented code block.
        final String input = ""
                + "1.  **Cost:** Description.\n"
                + "    $$\n"
                + "    C = \\sum H_i\n"
                + "    $$\n"
                + "    Where $H_i$ is the hours for task $i$.\n";
        final String expected = ""
                + "1.  **Cost:** Description.\n"
                + "    ```math\n"
                + "    C = \\sum H_i\n"
                + "    ```\n"
                + "    Where $H_i$ is the hours for task $i$.\n";
        assertEquals( expected, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testMultiLineDisplayMath() {
        final String input = "Text\n\n$$\n\\begin{align}\na &= b + c \\\\\nd &= e + f\n\\end{align}\n$$\n\nDone";
        final String expected = "Text\n\n```math\n\\begin{align}\na &= b + c \\\\\nd &= e + f\n\\end{align}\n```\n\nDone";
        assertEquals( expected, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testNoMathContent() {
        final String input = "Just some regular markdown text.\n\nWith paragraphs.";
        assertEquals( input, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testNullInput() {
        assertEquals( null, DisplayMathPreProcessor.transform( null ) );
    }

    @Test
    public void testEmptyInput() {
        assertEquals( "", DisplayMathPreProcessor.transform( "" ) );
    }

    @Test
    public void testDisplayMathAtStartOfDocument() {
        final String input = "$$\nx^2\n$$\n\nFollowing text";
        final String expected = "```math\nx^2\n```\n\nFollowing text";
        assertEquals( expected, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testDisplayMathAtEndOfDocument() {
        final String input = "Leading text\n\n$$\nx^2\n$$";
        final String expected = "Leading text\n\n```math\nx^2\n```";
        assertEquals( expected, DisplayMathPreProcessor.transform( input ) );
    }

    @Test
    public void testWindowsLineEndings() {
        final String input = "Text\r\n\r\n$$\r\n\\int_0^1 f(x)\\,dx\r\n$$\r\n\r\nMore";
        final String expected = "Text\r\n\r\n```math\n\\int_0^1 f(x)\\,dx\n```\r\n\r\nMore";
        assertEquals( expected, DisplayMathPreProcessor.transform( input ) );
    }

}
