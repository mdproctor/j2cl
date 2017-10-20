/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.j2cl.transpiler.integration.jsinteroptests;

import static jsinterop.annotations.JsPackage.GLOBAL;

import jsinterop.annotations.JsConstructor;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsType;

public class JsTypeVarargsTest extends MyTestCase {
  public static void testAll() {
    JsTypeVarargsTest test = new JsTypeVarargsTest();
    test.testVarargsCall_constructors();
    test.testVarargsCall_fromJavaScript();
    test.testVarargsCall_jsFunction();
    test.testVarargsCall_regularMethods();
    test.testVarargsCall_edgeCases();
    test.testVarargsCall_superCalls();
    test.testVarargsCall_sideEffectingInstance();
    test.testVarargsCall_correctArrayType();
  }

  @JsMethod
  private static native int varargsLengthThruArguments(Object... varargs);

  @JsMethod
  private static int varargsLength(Object... varargs) {
    return varargs.length;
  }

  @JsMethod
  private static int stringVarargsLength(String... varargs) {
    return varargs.length;
  }

  @JsMethod
  private int stringVarargsLengthV2(int i, String... varargs) {
    return varargs.length;
  }

  @JsMethod
  private static Object getVarargsSlot(int slot, Object... varargs) {
    return varargs[slot];
  }

  @JsMethod
  private Object[] clrearVarargsSlot(int slot, Object... varargs) {
    varargs[slot] = null;
    return varargs;
  }

  @JsMethod
  private Class<?> getVarargsArrayClass(String... varargs) {
    return varargs.getClass();
  }

  @JsMethod
  private static native Object callGetVarargsSlotUsingJsName();

  @JsType(isNative = true, namespace = GLOBAL, name = "Object")
  static class NativeJsType {}

  @JsType(
    isNative = true,
    namespace = "test.foo",
    name = "JsTypeVarargsTest_MyNativeJsTypeVarargsConstructor"
  )
  static class NativeJsTypeWithVarargsConstructor {
    public Object a;
    public int b;

    NativeJsTypeWithVarargsConstructor(int i, Object... args) {}
  }

  static class SubNativeWithVarargsConstructor extends NativeJsTypeWithVarargsConstructor {
    SubNativeWithVarargsConstructor(String s, Object... args) {
      this(1, args[0], args[1], null);
    }

    @JsConstructor
    SubNativeWithVarargsConstructor(int i, Object... args) {
      super(i, args);
    }

    @JsMethod
    Object varargsMethod(int i, Object... args) {
      return args[i];
    }
  }

  static class SubSubNativeWithVarargsConstructor extends SubNativeWithVarargsConstructor {
    @JsConstructor
    SubSubNativeWithVarargsConstructor() {
      super(0, new Object());
    }

    @Override
    Object varargsMethod(int i, Object... args) {
      return super.varargsMethod(i, args);
    }

    Object nonJsVarargsMethod() {
      return super.varargsMethod(1, null, this);
    }
  }

  public void testVarargsCall_regularMethods() {
    assertEquals(3, varargsLengthThruArguments("A", "B", "C"));
    assertEquals(4, varargsLength("A", "B", "C", "D"));
    assertEquals(2, varargsLengthThruArguments(new NativeJsType[] {null, null}));
    assertEquals(5, varargsLength(new NativeJsType[] {null, null, null, null, null}));
    assertEquals("C", getVarargsSlot(2, "A", "B", "C", "D"));
    assertEquals("3", callGetVarargsSlotUsingJsName());
    assertNull(clrearVarargsSlot(1, "A", "B", "C")[1]);
    assertEquals("A", clrearVarargsSlot(1, "A", "B", "C")[0]);
    assertEquals(3, clrearVarargsSlot(1, "A", "B", "C").length);
    assertSame(String[].class, getVarargsArrayClass("A", "B", "C"));
  }

  public void testVarargsCall_edgeCases() {
    assertSame(String[].class, getVarargsArrayClass());
    assertSame(String[].class, getVarargsArrayClass(new String[0]));
    assertSame(String[].class, getVarargsArrayClass((String) null));
    try {
      assertSame(String[].class, getVarargsArrayClass(null));
      fail("Should have thrown exception");
    } catch (NullPointerException expected) {
    }
    try {
      assertSame(String[].class, getVarargsArrayClass((String[]) null));
      fail("Should have thrown exception");
    } catch (NullPointerException expected) {
    }

    assertEquals(0, stringVarargsLength());
    assertEquals(0, stringVarargsLength(new String[0]));
    assertEquals(1, stringVarargsLength((String) null));
    try {
      assertEquals(0, stringVarargsLength(null));
      fail("Should have thrown exception");
    } catch (NullPointerException expected) {
    }
    try {
      assertEquals(0, stringVarargsLength((String[]) null));
      fail("Should have thrown exception");
    } catch (NullPointerException expected) {
    }

    // Test with an additional parameter as it results in a slightly different call site.
    assertEquals(0, stringVarargsLengthV2(0));
    assertEquals(0, stringVarargsLengthV2(0, new String[0]));
    assertEquals(1, stringVarargsLengthV2(0, (String) null));
    try {
      assertEquals(0, stringVarargsLengthV2(0, null));
      fail("Should have thrown exception");
    } catch (NullPointerException expected) {
    }
    try {
      assertEquals(0, stringVarargsLengthV2(0, (String[]) null));
      fail("Should have thrown exception");
    } catch (NullPointerException expected) {
    }
  }

  public void testVarargsCall_constructors() {
    NativeJsType someNativeObject = new NativeJsType();
    NativeJsTypeWithVarargsConstructor object =
        new NativeJsTypeWithVarargsConstructor(1, someNativeObject, null);

    assertSame(someNativeObject, object.a);
    assertEquals(3, object.b);

    Object[] params = new Object[] {someNativeObject, null};
    object = new NativeJsTypeWithVarargsConstructor(1, params);

    assertSame(someNativeObject, object.a);
    assertEquals(3, object.b);

    object = new SubNativeWithVarargsConstructor(1, someNativeObject, null);

    assertSame(someNativeObject, object.a);
    assertEquals(3, object.b);
  }

  @JsMethod
  public static Double sumAndMultiply(Double multiplier, Double... numbers) {
    double result = 0.0d;
    for (double d : numbers) {
      result += d;
    }
    result *= multiplier;
    return result;
  }

  @JsMethod
  public static int sumAndMultiplyInt(int multiplier, int... numbers) {
    int result = 0;
    for (int d : numbers) {
      result += d;
    }
    result *= multiplier;
    return result;
  }

  @JsFunction
  interface Function {
    Object f(int i, Object... args);
  }

  static final class AFunction implements Function {

    @Override
    public Object f(int i, Object... args) {
      return args[i];
    }

    static Function create() {
      return new AFunction();
    }
  }

  public void testVarargsCall_fromJavaScript() {
    assertEquals(60, callSumAndMultiply());
    assertEquals(30, callSumAndMultiplyInt());
    Function f = AFunction.create();
    assertSame(f, callAFunction(f));
  }

  @JsMethod
  private static native int callSumAndMultiply();

  @JsMethod
  private static native int callSumAndMultiplyInt();

  @JsMethod
  private static native Object callAFunction(Object obj);

  public void testVarargsCall_jsFunction() {
    Function function = new AFunction();
    assertSame(function, function.f(2, null, null, function, null));
    assertSame(null, function.f(1, null, null, function, null));
  }

  public void testVarargsCall_superCalls() {
    SubSubNativeWithVarargsConstructor object = new SubSubNativeWithVarargsConstructor();
    assertSame(object, object.nonJsVarargsMethod());
    assertSame(object, object.varargsMethod(1, null, object, null));
  }

  private static int sideEffectCount;

  private SubNativeWithVarargsConstructor doSideEffect(SubNativeWithVarargsConstructor obj) {
    sideEffectCount++;
    return obj;
  }

  public void testVarargsCall_sideEffectingInstance() {
    Object arg = new Object();
    SubNativeWithVarargsConstructor object = new SubNativeWithVarargsConstructor(0, arg);
    sideEffectCount = 0;
    Object[] params = new Object[] {object, null};
    assertSame(object, doSideEffect(object).varargsMethod(0, params));
    assertSame(1, sideEffectCount);
  }

  @JsFunction
  interface JsStringConsumer {
    void consume(String... strings);
  }

  public void testVarargsCall_correctArrayType() {
    JsStringConsumer consumer = (strings) -> assertTrue(strings instanceof String[]);
    consumer.consume("A", "B");
  }
}