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
package com.wikantik.pages.haddock;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;

/**
 * Helpers for driving React controlled inputs from Selenide tests.
 *
 * <p>React tracks the "last known value" of controlled inputs via an internal
 * value tracker attached to the DOM node, and suppresses {@code onChange}
 * callbacks when a change event fires with a value that matches the tracked
 * value. Selenide's {@code .val()} / WebDriver's {@code sendKeys} (and even
 * {@code Configuration.fastSetValue = true}) can update the DOM input's
 * {@code value} attribute without going through the React value tracker —
 * React then thinks no change happened, skips the {@code onChange} handler,
 * and the component's state stays empty. The form submits with empty state
 * and the backend rejects it, even though the input visually looks filled in.
 *
 * <p>The canonical workaround is to reach into
 * {@code HTMLInputElement.prototype} (or {@code HTMLTextAreaElement.prototype}
 * for textareas) to call the native value setter directly — this invalidates
 * React's value tracker — and then dispatch a bubbling {@code input} event so
 * React's synthetic event pipeline reruns the onChange handler. After this,
 * the React component's state mirrors the DOM.
 */
final class ReactInputs {

    private ReactInputs() {
        // utility class
    }

    /**
     * Set a value on a React controlled {@code <input>} in a way that triggers
     * React's {@code onChange}.
     */
    static void setInputValue( final SelenideElement element, final String value ) {
        setValue( element, value, "HTMLInputElement" );
    }

    /**
     * Set a value on a React controlled {@code <textarea>} in a way that
     * triggers React's {@code onChange}.
     */
    static void setTextareaValue( final SelenideElement element, final String value ) {
        setValue( element, value, "HTMLTextAreaElement" );
    }

    private static void setValue( final SelenideElement element, final String value, final String prototype ) {
        Selenide.executeJavaScript(
            "var el = arguments[0];"
            + " var desc = Object.getOwnPropertyDescriptor(window." + prototype + ".prototype, 'value');"
            + " desc.set.call(el, arguments[1]);"
            + " el.dispatchEvent(new Event('input', { bubbles: true }));",
            element, value );
    }

}
