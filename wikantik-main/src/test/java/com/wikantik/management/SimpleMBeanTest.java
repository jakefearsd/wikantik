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
package com.wikantik.management;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.NotCompliantMBeanException;
import javax.management.ReflectionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SimpleMBean} — an abstract DynamicMBean that exposes
 * methods via reflection.  A concrete {@link TestMBean} subclass is used
 * to exercise construction, attribute access, batch operations, and
 * operation invocation without any wiki engine dependency.
 */
class SimpleMBeanTest {

    /** Concrete subclass used by most tests. */
    private static class TestMBean extends SimpleMBean {

        private String foo = "default";
        private int counter = 0;
        private boolean active = false;

        TestMBean() throws NotCompliantMBeanException {
            super();
        }

        public String getFoo() { return foo; }
        public void setFoo( final String foo ) { this.foo = foo; }
        public String getFooDescription() { return "A test attribute"; }

        public int getCounter() { return counter; }
        // No setter — read-only attribute

        public boolean isActive() { return active; }
        public void setActive( final boolean active ) { this.active = active; }

        public String doSomething() { return "done"; }
        public String echo( final String input ) { return input; }

        @Override
        public String[] getAttributeNames() {
            return new String[] { "foo", "counter", "active" };
        }

        @Override
        public String[] getMethodNames() {
            return new String[] { "doSomething", "echo" };
        }
    }

    /**
     * A broken MBean that declares an operation name that does not
     * correspond to any real method, triggering {@link NotCompliantMBeanException}.
     */
    private static class BrokenMethodMBean extends SimpleMBean {

        BrokenMethodMBean() throws NotCompliantMBeanException {
            super();
        }

        @Override
        public String[] getAttributeNames() {
            return new String[0];
        }

        @Override
        public String[] getMethodNames() {
            return new String[] { "thisMethodDoesNotExist" };
        }
    }

    private TestMBean mbean;

    @BeforeEach
    void setUp() throws NotCompliantMBeanException {
        mbean = new TestMBean();
    }

    // ---------------------------------------------------------------
    //  Construction & MBeanInfo
    // ---------------------------------------------------------------

    @Test
    void constructionSucceeds() {
        assertNotNull( mbean );
    }

    @Test
    void mbeanInfoHasCorrectNumberOfAttributes() {
        final MBeanInfo info = mbean.getMBeanInfo();
        assertEquals( 3, info.getAttributes().length,
                "Should expose foo, counter, and active" );
    }

    @Test
    void mbeanInfoHasCorrectNumberOfOperations() {
        final MBeanInfo info = mbean.getMBeanInfo();
        assertEquals( 2, info.getOperations().length,
                "Should expose doSomething and echo" );
    }

    @Test
    void fooAttributeIsReadWrite() {
        final MBeanAttributeInfo fooInfo = findAttribute( "foo" );
        assertNotNull( fooInfo, "foo attribute should exist" );
        assertTrue( fooInfo.isReadable(), "foo should be readable" );
        assertTrue( fooInfo.isWritable(), "foo should be writable" );
    }

    @Test
    void counterAttributeIsReadOnly() {
        final MBeanAttributeInfo counterInfo = findAttribute( "counter" );
        assertNotNull( counterInfo, "counter attribute should exist" );
        assertTrue( counterInfo.isReadable(), "counter should be readable" );
        assertFalse( counterInfo.isWritable(), "counter should NOT be writable" );
    }

    @Test
    void activeAttributeIsReadableViaIsPrefix() {
        final MBeanAttributeInfo activeInfo = findAttribute( "active" );
        assertNotNull( activeInfo, "active attribute should exist" );
        assertTrue( activeInfo.isReadable(),
                "active should be readable (isActive getter found during construction)" );
        // Note: isIs() returns true when the getter uses the "is" prefix
        assertTrue( activeInfo.isIs(),
                "active should be marked as using the 'is' prefix getter" );
    }

    @Test
    void fooDescriptionIsWiredUp() {
        final MBeanAttributeInfo fooInfo = findAttribute( "foo" );
        assertNotNull( fooInfo );
        assertEquals( "A test attribute", fooInfo.getDescription() );
    }

    @Test
    void counterDescriptionIsEmptyByDefault() {
        final MBeanAttributeInfo counterInfo = findAttribute( "counter" );
        assertNotNull( counterInfo );
        assertEquals( "", counterInfo.getDescription(),
                "Attributes without a getXxxDescription() method should have an empty description" );
    }

    @Test
    void mbeanInfoClassNameMatchesConcrete() {
        final MBeanInfo info = mbean.getMBeanInfo();
        assertEquals( TestMBean.class.getName(), info.getClassName() );
    }

    // ---------------------------------------------------------------
    //  getAttribute
    // ---------------------------------------------------------------

    @Test
    void getAttributeReturnsFooValue() throws Exception {
        assertEquals( "default", mbean.getAttribute( "foo" ) );
    }

    @Test
    void getAttributeReturnsCounterValue() throws Exception {
        assertEquals( 0, mbean.getAttribute( "counter" ) );
    }

    /**
     * Exposes a defect in {@code getAttribute}: the method only looks
     * for a {@code getXxx()} getter and never falls back to
     * {@code isXxx()}, unlike the constructor which correctly checks
     * both.  As a result, boolean attributes using the {@code is}
     * prefix are reported as readable in the MBeanInfo but cannot
     * actually be read via {@code getAttribute()}.
     */
    @Test
    void getAttributeActiveThrowsBecauseIsPrefixNotChecked() {
        // This SHOULD return false (the attribute IS declared and has an isActive getter),
        // but getAttribute() only tries "get" + name, so it throws.
        assertThrows( AttributeNotFoundException.class,
                () -> mbean.getAttribute( "active" ),
                "getAttribute does not check 'is' prefix — this is a known bug" );
    }

    @Test
    void getAttributeThrowsForNonexistent() {
        assertThrows( AttributeNotFoundException.class,
                () -> mbean.getAttribute( "nonexistent" ) );
    }

    // ---------------------------------------------------------------
    //  setAttribute
    // ---------------------------------------------------------------

    @Test
    void setAttributeChangesFoo() throws Exception {
        mbean.setAttribute( new Attribute( "foo", "newValue" ) );
        assertEquals( "newValue", mbean.getFoo() );
    }

    @Test
    void setAttributeThrowsForReadOnlyCounter() {
        // counter has no setter, so setAttribute should not find one
        assertThrows( AttributeNotFoundException.class,
                () -> mbean.setAttribute( new Attribute( "counter", 42 ) ) );
    }

    @Test
    void setAttributeThrowsForNonexistent() {
        assertThrows( AttributeNotFoundException.class,
                () -> mbean.setAttribute( new Attribute( "nonexistent", "value" ) ) );
    }

    /**
     * Exposes a defect in {@code setAttribute}: the method uses
     * {@code attr.getValue().getClass()} (which returns the wrapper
     * type {@code Boolean.class}) to look up the setter via
     * {@code getDeclaredMethod}.  Because {@code setActive} is
     * declared with the primitive parameter {@code boolean}, the
     * reflective lookup fails and the setter is not found.
     */
    @Test
    void setAttributeActiveFailsDueToPrimitiveWrapperMismatch() {
        // setAttribute passes Boolean.class to getDeclaredMethod, but setActive takes boolean
        assertThrows( AttributeNotFoundException.class,
                () -> mbean.setAttribute( new Attribute( "active", Boolean.TRUE ) ),
                "setAttribute cannot find setActive(boolean) when looking for setActive(Boolean) — known bug" );
    }

    // ---------------------------------------------------------------
    //  getAttributes / setAttributes (batch)
    // ---------------------------------------------------------------

    @Test
    void getAttributesBatchReturnsBothValues() {
        final AttributeList list = mbean.getAttributes( new String[] { "foo", "counter" } );
        assertEquals( 2, list.size(), "Both foo and counter should be returned" );

        final Attribute first = ( Attribute ) list.get( 0 );
        final Attribute second = ( Attribute ) list.get( 1 );
        assertEquals( "foo", first.getName() );
        assertEquals( "default", first.getValue() );
        assertEquals( "counter", second.getName() );
        assertEquals( 0, second.getValue() );
    }

    @Test
    void getAttributesBatchSkipsInvalidNames() {
        final AttributeList list = mbean.getAttributes(
                new String[] { "foo", "nonexistent", "counter" } );
        // "nonexistent" is silently skipped; only foo and counter are returned
        assertEquals( 2, list.size() );
    }

    @Test
    void setAttributesBatchReturnsOnlySuccesses() {
        final AttributeList input = new AttributeList();
        input.add( new Attribute( "foo", "batchValue" ) );
        input.add( new Attribute( "counter", 99 ) );      // read-only — will fail
        input.add( new Attribute( "nonexistent", "x" ) );  // no such attribute — will fail

        final AttributeList result = mbean.setAttributes( input );

        assertEquals( 1, result.size(), "Only foo should succeed" );
        final Attribute success = ( Attribute ) result.get( 0 );
        assertEquals( "foo", success.getName() );
        assertEquals( "batchValue", success.getValue() );
        // Verify the value was actually applied
        assertEquals( "batchValue", mbean.getFoo() );
    }

    @Test
    void setAttributesBatchWithAllValid() throws Exception {
        final AttributeList input = new AttributeList();
        input.add( new Attribute( "foo", "first" ) );

        final AttributeList result = mbean.setAttributes( input );
        assertEquals( 1, result.size() );
        assertEquals( "first", mbean.getFoo() );
    }

    // ---------------------------------------------------------------
    //  invoke
    // ---------------------------------------------------------------

    @Test
    void invokeDoSomethingReturnsResult() throws Exception {
        final Object result = mbean.invoke( "doSomething", null, null );
        assertEquals( "done", result );
    }

    @Test
    void invokeEchoPassesArgument() throws Exception {
        final Object result = mbean.invoke( "echo", new Object[] { "hello" }, null );
        assertEquals( "hello", result );
    }

    @Test
    void invokeNonexistentThrowsReflectionException() {
        assertThrows( ReflectionException.class,
                () -> mbean.invoke( "nonexistent", null, null ) );
    }

    @Test
    void invokeWithWrongArgumentsThrowsReflectionException() {
        // echo expects a String; passing an Integer should fail
        assertThrows( ReflectionException.class,
                () -> mbean.invoke( "echo", new Object[] { 42 }, null ) );
    }

    // ---------------------------------------------------------------
    //  Error case — bad method declaration
    // ---------------------------------------------------------------

    @Test
    void constructionThrowsWhenDeclaredMethodDoesNotExist() {
        assertThrows( NotCompliantMBeanException.class, BrokenMethodMBean::new );
    }

    // ---------------------------------------------------------------
    //  Edge cases
    // ---------------------------------------------------------------

    @Test
    void getAttributesWithEmptyArrayReturnsEmptyList() {
        final AttributeList list = mbean.getAttributes( new String[0] );
        assertNotNull( list );
        assertTrue( list.isEmpty() );
    }

    @Test
    void setAttributesWithEmptyListReturnsEmptyList() {
        final AttributeList result = mbean.setAttributes( new AttributeList() );
        assertNotNull( result );
        assertTrue( result.isEmpty() );
    }

    @Test
    void setAttributeUpdatesValueMultipleTimes() throws Exception {
        mbean.setAttribute( new Attribute( "foo", "v1" ) );
        assertEquals( "v1", mbean.getFoo() );
        mbean.setAttribute( new Attribute( "foo", "v2" ) );
        assertEquals( "v2", mbean.getFoo() );
        mbean.setAttribute( new Attribute( "foo", "v3" ) );
        assertEquals( "v3", mbean.getFoo() );
    }

    @Test
    void operationInfoNamesMatchDeclaredMethods() {
        final MBeanOperationInfo[] ops = mbean.getMBeanInfo().getOperations();
        assertEquals( "doSomething", ops[0].getName() );
        assertEquals( "echo", ops[1].getName() );
    }

    @Test
    void mbeanInfoDescriptionIsEmpty() {
        // SimpleMBean.getDescription() returns ""
        assertEquals( "", mbean.getMBeanInfo().getDescription() );
    }

    // ---------------------------------------------------------------
    //  Helper
    // ---------------------------------------------------------------

    private MBeanAttributeInfo findAttribute( final String name ) {
        for ( final MBeanAttributeInfo attr : mbean.getMBeanInfo().getAttributes() ) {
            if ( attr.getName().equals( name ) ) {
                return attr;
            }
        }
        return null;
    }
}
