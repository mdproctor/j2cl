package com.google.j2cl.transpiler.incremental;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import com.google.j2cl.incremental.Dependency;
import com.google.j2cl.incremental.TypeInfo;
import com.google.j2cl.transpiler.integration.TranspilerTester;
import com.google.j2cl.transpiler.integration.TranspilerTester.TranspileResult;
import com.google.j2cl.incremental.IncrementalManager;
import org.junit.Test;

import static org.junit.Assert.*;

import static com.google.j2cl.transpiler.integration.TranspilerTester.newTesterWithDefaults;

public class IncrementalTest {

    private String code(String... code) {
        List<String> content = new ArrayList<>(Arrays.asList(code));
        return Joiner.on('\n').join(content);
    }

    @Test
    public void testDependencyGraphWasBuiltCorrectly() throws IOException {
        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public int getJ() {return j;}",
                           ///"  public class InnerFoo {}",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
                           "}");

        String foo3 = code("public class Foo3 {",
                           "  public String m1(int i, String s) { return \"xxx\"; } ",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "      Foo3 foo3 = new Foo3();",
                "  } ",
                "  public class InnerBar {}",
                "}");

        TranspileResult results = Helper.create()
                                        .add("Foo1", foo1)
                                        .add("Foo2", foo2)
                                        .add("Foo3", foo3)
                                        .add("Bar", bar)
                                        .assertTranspileSucceeds();

        IncrementalManager refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1", "test.Foo2", "test.Foo3");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Bar$InnerBar", "test.Foo1", "test.Foo2", "test.Foo3", "java.lang.Object");

        Set<TypeInfo>    impact = getTypesImpactCaller(refMan);
        assertEquals(2, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo2"} ), result); // Foo3 is not part of this, because has no naming changing JsInterop annot.

        TypeInfo foo1Info = refMan.get("?test.Foo1");
        List<String> callers = asCallerListOfString(foo1Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);  // Only has new Class

        TypeInfo foo2Info = refMan.get("?test.Foo2");
        callers = asCallerListOfString(foo2Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar"} ), callers);  // repeats twice, one for Method one for new Class

        TypeInfo foo3Info = refMan.get("?test.Foo3");
        callers = asCallerListOfString(foo3Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);

        TypeInfo barInfo = refMan.get("?test.Bar");
        callers = asCallerListOfString(barInfo.getIncomingDependencies());
        assertTrue(callers.isEmpty());

        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
        Collections.sort(callees);
        // Foo2 is repeated twice, one for new Foo2 and once for helloWorld method call
        // Foo1 is instantiate twice, but we keep it as a Set, as it only needs to know the relationship usage type once
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo2", "Foo2", "Foo3"} ), callees);
    }

    @Test
    public void testRemoveTypeAndUpdateUsage() throws IOException {
        // later removes Foo and updates Bar

        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
                           "  public class InnerFoo {}",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
                           "}");

        String foo3 = code("public class Foo3 {",
                           "  public String m1(int i, String s) { return \"xxx\"; } ",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "      Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
                "      Foo3 foo3 = new Foo3();",
                "  } ",
                "  public class InnerBar {}",
                "}");

        String bar2 = code( // No longer uses Foo1, that was deleted, but also no longer uses Foo3, which is still there.
                "public class Bar {",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "  } ",
                "  public class InnerBar {}",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.add("Foo1", foo1)
                                        .add("Foo2", foo2)
                                        .add("Foo3", foo3)
                                        .add("Bar", bar)
                                        .assertTranspileSucceeds();

        IncrementalManager refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1", "test.Foo2", "test.Foo3");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Bar$InnerBar", "test.Foo1", "test.Foo1$InnerFoo", "test.Foo2", "test.Foo3", "java.lang.Object");

        Set<TypeInfo>    impact = getTypesImpactCaller(refMan);
        assertEquals(2, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo2"} ), result);  // Foo3 is not part of this, because has no naming changing JsInterop annot.

        TypeInfo foo3Info = refMan.get("?test.Foo3");
        List<String> callers = asCallerListOfString(foo3Info.getIncomingDependencies());
        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());
        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);  // repeats twice, one for Method one for new Class

        results = helper.remove("Foo1")
                        .add("Bar", bar2)
                        .assertTranspileSucceeds();

        refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Bar");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Bar$InnerBar", "test.Foo2", "test.Foo3", "java.lang.Object");

        impact = getTypesImpactCaller(refMan);
        assertEquals(1, impact.size());
        result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"Foo2"} ), result); // Only Foo2 left

        TypeInfo foo2Info = refMan.get("?test.Foo2");
        callers = asCallerListOfString(foo2Info.getIncomingDependencies());
        assertTrue(foo2Info.getOutgoingDependencies().isEmpty());
        assertEquals(Arrays.asList(new String[] {"Bar", "Bar"} ), callers);  // repeats twice, one for Method one for new Class

        foo3Info = refMan.get("?test.Foo3"); // Bar called it before, it doesn't any more
        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());
        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());

        TypeInfo barInfo = refMan.get("?test.Bar");
        callers = asCallerListOfString(barInfo.getIncomingDependencies());
        assertTrue(callers.isEmpty());

        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
        Collections.sort(callees);
        // Foo2 is repeated twice, one for new Foo2 and once for helloWorld method call
        // Foo1 and Foo2 are not longer called.
        assertEquals(Arrays.asList(new String[] {"Foo2", "Foo2"} ), callees);
    }

    @Test
    public void testInnerTypesConstruction() throws IOException  {
        // This test exhaustively asserts on the structure of the Dependencies.
        // Later tests take the same sources, but removes and updates parts

        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public void method1() { }",
                           "  public class InnerFoo1 {" +
                           "    public void innerMethod2() { }",
                           "    public class InnerInnerFoo1Fum1 {" +
                           "      public void innerInnerMethod3() { }",
                           "    }",
                           "    public class InnerInnerFoo1Fum2 {" +
                           "      public void innerInnerMethod4() { }",
                           "    }",
                           "  }",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.add("Foo1", foo1)
                                        .add("Bar", bar)
                                        .assertTranspileSucceeds();

        IncrementalManager refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Foo1", "test.Foo1$InnerFoo1",
                                 "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2", "java.lang.Object");

        Set<TypeInfo>    impact = getTypesImpactCaller(refMan);
        assertEquals(1, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"Foo1"} ), result);

        TypeInfo barInfo = refMan.get("?test.Bar");
        assertTrue(barInfo.getIncomingDependencies().isEmpty());

        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
        Collections.sort(callees);
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo1", "InnerFoo1",
                                                 "InnerInnerFoo1Fum1", "InnerInnerFoo1Fum2"} ), callees);

        TypeInfo foo1Info = refMan.get("?test.Foo1");
        assertTrue(foo1Info.getOutgoingDependencies().isEmpty());
        List<String> callers = asCallerListOfString(foo1Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar","Bar"} ), callers);

        assertEquals(1, foo1Info.getInnerTypes().size());
        TypeInfo foo2Info = foo1Info.getInnerTypes().get(0);
        assertTrue(foo2Info.getOutgoingDependencies().isEmpty());
        assertEquals(foo1Info, foo2Info.getEnclosingTypeInfo());
        callers = asCallerListOfString(foo2Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);

        assertEquals(2, foo2Info.getInnerTypes().size());
        TypeInfo foo3Info = foo2Info.getInnerTypes().get(0);
        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());
        assertEquals(foo2Info, foo3Info.getEnclosingTypeInfo() );
        assertEquals(foo2Info, foo3Info.getEnclosingTypeInfo() );
        callers = asCallerListOfString(foo3Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);

        TypeInfo foo4Info = foo2Info.getInnerTypes().get(1);
        assertTrue(foo4Info.getOutgoingDependencies().isEmpty());
        assertEquals(foo2Info, foo4Info.getEnclosingTypeInfo() );
        callers = asCallerListOfString(foo4Info.getIncomingDependencies());
        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);
    }

    @Test
    public void testInnerTypesWithBarRemoval() throws IOException  {
        // Further testing testInnerTypesConstruction but with Bar removal

        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public void method1() { }",
                           "  public class InnerFoo1 {" +
                           "    public void innerMethod2() { }",
                           "    public class InnerInnerFoo1Fum1 {" +
                           "      public void innerInnerMethod3() { }",
                           "    }",
                           "    public class InnerInnerFoo1Fum2 {" +
                           "      public void innerInnerMethod4() { }",
                           "    }",
                           "  }",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.add("Foo1", foo1)
                                        .add("Bar", bar)
                                        .assertTranspileSucceeds();

        IncrementalManager refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Foo1", "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1",
                                 "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2", "java.lang.Object");

        TypeInfo foo1Info = refMan.get("?test.Foo1");
        TypeInfo foo2Info = refMan.get("?test.Foo1$InnerFoo1");
        TypeInfo foo3Info = refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1");
        TypeInfo foo4Info = refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");

        // all false
        assertFalse(asCallerListOfString(foo1Info.getIncomingDependencies()).isEmpty());
        assertFalse(asCallerListOfString(foo2Info.getIncomingDependencies()).isEmpty());
        assertFalse(asCallerListOfString(foo3Info.getIncomingDependencies()).isEmpty());
        assertFalse(asCallerListOfString(foo4Info.getIncomingDependencies()).isEmpty());

        results = helper.remove("Bar")
                        .assertTranspileSucceeds();

        // No need to test data structures here, as they are tested in testInnerTypesConstruction
        // Now test Bar removal
        refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan);
        assertContainedTypeInfos(refMan, "test.Foo1", "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1",
                                 "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2", "java.lang.Object");

        foo1Info = refMan.get("?test.Foo1");
        foo2Info = refMan.get("?test.Foo1$InnerFoo1");
        foo3Info = refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1");
        foo4Info = refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");

        // all true
        assertTrue(asCallerListOfString(foo1Info.getIncomingDependencies()).isEmpty());
        assertTrue(asCallerListOfString(foo2Info.getIncomingDependencies()).isEmpty());
        assertTrue(asCallerListOfString(foo3Info.getIncomingDependencies()).isEmpty());
        assertTrue(asCallerListOfString(foo4Info.getIncomingDependencies()).isEmpty());
    }

    @Test
    public void testInnerTypesWithFooRemoval() throws IOException  {
        // Further testing testInnerTypesConstruction but with Bar update and Foo removal

        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public void method1() { }",
                           "  public class InnerFoo1 {" +
                           "    public void innerMethod2() { }",
                           "    public class InnerInnerFoo1Fum1 {" +
                           "      public void innerInnerMethod3() { }",
                           "    }",
                           "    public class InnerInnerFoo1Fum2 {" +
                           "      public void innerInnerMethod4() { }",
                           "    }",
                           "  }",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo1 foo1 = new Foo1();",
                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
                "  } ",
                "}");

        String bar2 = code(
                "public class Bar {",
                "  public Bar() { }",
                "  public void method1() {",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.add("Foo1", foo1)
                                        .add("Bar", bar)
                                        .assertTranspileSucceeds();

        IncrementalManager refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");
        assertContainedTypeInfos(refMan, "test.Bar", "test.Foo1", "test.Foo1$InnerFoo1", "test.Foo1$InnerFoo1$InnerInnerFoo1Fum1",
                                 "test.Foo1$InnerFoo1$InnerInnerFoo1Fum2", "java.lang.Object");
        Set<TypeInfo>    impact = getTypesImpactCaller(refMan);
        assertEquals(1, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"Foo1"} ), result);

        TypeInfo barInfo = refMan.get("?test.Bar");
        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
        Collections.sort(callees);
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo1", "InnerFoo1", "InnerInnerFoo1Fum1", "InnerInnerFoo1Fum2"} ), callees);

        assertNotNull(refMan.get("?test.Foo1"));
        assertNotNull(refMan.get("?test.Foo1$InnerFoo1"));
        assertNotNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1"));
        assertNotNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2"));

        // Now test Foo removal
        results = helper.add("Bar", bar2)
                        .remove("Foo1")
                        .assertTranspileSucceeds();

        refMan = results.getIncrementalManager();
        assertEquals(0, getTypesImpactCaller(refMan).size());
        assertTranspiledFiles(refMan, "test.Bar");
        assertContainedTypeInfos(refMan, "test.Bar", "java.lang.Object");
        barInfo = refMan.get("?test.Bar");

        assertTrue(barInfo.getIncomingDependencies().isEmpty());
        assertTrue(barInfo.getOutgoingDependencies().isEmpty());

        assertNull(refMan.get("?test.Foo1"));
        assertNull(refMan.get("?test.Foo1$InnerFoo1"));
        assertNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1"));
        assertNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2"));
    }


    @Test
    public void testContinuationAfterError() throws IOException {
        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
                           "  public class InnerFoo {}",
                           "}");

        String foo1_1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public void getJ() { return j;}",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
                           "}");

        String foo2_1 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return 5; } ",
                           "}");

        String foo3 = code("public class Foo3 {",
                           "  public String m1(int i, String s) { return \"xxx\"; } ",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "      Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
                "  } ",
                "}");

        String bar2 = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "      Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
                "      Foo3 foo3 = new Foo3();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.add("Foo1", foo1)
                                        .add("Foo2", foo2)
                                        .add("Bar", bar)
                                        .assertTranspileSucceeds();

        IncrementalManager refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1", "test.Foo2");

        results = helper.add("Foo1", foo1_1)
                        .assertTranspileFails();  // note it failed

        results = helper.add("Foo2", foo2_1)
                        .add("Foo3", foo3)
                        .add("Bar", bar2)
                        .assertTranspileFails();  // note it failed


        results = helper.add("Foo1", foo1)
                        .add("Foo2", foo2)
                        .add("Foo3", foo3)
                        .add("Bar", bar2)
                        .assertTranspileSucceeds(); // tbis time it succeeds

        refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1", "test.Foo2", "test.Foo3");
    }

    @Test
    public void testImpactUpdated() throws IOException {
        String foo1 = code("import jsinterop.annotations.JsType;",
                           "@JsType(name = \"Faz\")",
                           "public class Foo1 {",
                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
                           "  public class InnerFoo {}",
                           "}");

        String foo2 = code("import jsinterop.annotations.JsMethod;",
                           "public class Foo2 {",
                           "  @JsMethod(name =\"goodBye\")",
                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
                           "}");

        String foo3 = code("public class Foo3 {",
                           "  public String m1(int i, String s) {",
                           "    Bar bar = new Bar();",
                           "    return \"xxx\";",
                           "  } ",
                           "}");

        String bar = code(
                "public class Bar { private Foo1 foo = new Foo1();",
                "  public Bar() { }",
                "  public void method1() {",
                "    Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
                "    Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
                "  } ",
                "}");

        Helper helper = Helper.create();
        TranspileResult results = helper.add("Foo1", foo1)
                                        .add("Foo2", foo2)
                                        .add("Foo3", foo3)
                                        .add("Bar", bar)
                                        .assertTranspileSucceeds();

        IncrementalManager refMan = results.getIncrementalManager();
        TypeInfo foo1Info = refMan.get("?test.Foo1");
        TypeInfo foo2Info = refMan.get("?test.Foo2");
        TypeInfo foo3Info = refMan.get("?test.Foo3");
        TypeInfo barInfo = refMan.get("?test.Bar");

        List<String> impacted = getTypesImpactCaller(refMan).stream().map( TypeInfo::getUniqueId).collect(Collectors.toList());
        assertEquals(2, impacted.size());
        assertTrue(impacted.contains("?test.Foo1"));
        assertTrue(impacted.contains("?test.Foo2"));

        // Foo3 and Bar are connected, but neither is in the impacted, as no JsInterop impact
        assertEquals(0, foo3Info.getIncomingDependencies().size());
        assertEquals(1, foo3Info.getOutgoingDependencies().size());

        // no dependencies are marked as having an impact
        List<Dependency> deps = foo3Info.getOutgoingDependencies().stream().filter(d -> d.isCalleeHasImpact()).collect(Collectors.toList());
        assertEquals(0, deps.size());

        // Foo1 has JsType so it impacts the caller Bar
        assertEquals(4, foo1Info.getIncomingDependencies().size());
        assertEquals(0, foo1Info.getOutgoingDependencies().size());
        deps = foo1Info.getIncomingDependencies().stream().filter(d -> d.isCalleeHasImpact()).collect(Collectors.toList());
        assertEquals(2, deps.size());

        // Foo2 has JsMethod so it impacts the caller Bar
        assertEquals(2, foo2Info.getIncomingDependencies().size());
        assertEquals(0, foo2Info.getOutgoingDependencies().size());
        deps = foo2Info.getIncomingDependencies().stream().filter(d -> d.isCalleeHasImpact()).collect(Collectors.toList());
        assertEquals(1, deps.size());

        // Check Bar, should be combined inverse of Foo1 and Foo2
        assertEquals(1, barInfo.getIncomingDependencies().size());
        assertFalse(barInfo.getIncomingDependencies().get(0).isCalleeHasImpact());
        assertEquals(6, barInfo.getOutgoingDependencies().size());
        deps = barInfo.getOutgoingDependencies().stream().filter(d -> d.isCalleeHasImpact()).collect(Collectors.toList());
        assertEquals(3, deps.size());

        // re-add Foo1, this impacts Bar
        results = helper.add("Foo1", foo1)
                        .assertTranspileSucceeds();

        refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Bar", "test.Foo1");

        // re-add Foo3, nothing impacted
        results = helper.add("Foo3", foo3)
                        .assertTranspileSucceeds();

        refMan = results.getIncrementalManager();
        assertTranspiledFiles(refMan, "test.Foo3");
    }

    private List<String> asListOfString(Set<TypeInfo> impact) {
        return impact.stream().map(t -> t.getType().getSimpleSourceName()).collect(Collectors.toList());
    }

    private List<String> asCalleeListOfString(List<Dependency> deps) {
        return deps.stream().map(t -> t.getCallee().getType().getSimpleSourceName()).collect(Collectors.toList());
    }

    private List<String> asCallerListOfString(List<Dependency> deps) {
        return deps.stream().map(t -> t.getCaller().getType().getSimpleSourceName()).collect(Collectors.toList());
    }
    private void assertTranspiledFiles(IncrementalManager refMan, String... types) {
        IncrementalManager finalRefMan = refMan;
        assertEquals(types.length, refMan.getSources().size()); // how many files did it transpile
        List<String> sources = refMan.getSources().stream().map(fileInfo -> finalRefMan.getChangeSet().getPathToUniqueId().get(fileInfo.originalPath())).collect(Collectors.toList());
        Arrays.stream(types).forEach(type -> assertTrue("Does not contain " + type, sources.contains("?" + type)));
    }

    private void assertContainedTypeInfos(IncrementalManager refMan, String... types) {
        assertEquals(types.length, refMan.getTypeInfoLookup().size()); // how many files did it transpile
        Arrays.stream(types).forEach(type ->
                                             assertTrue("Does not contain " + type, refMan.getTypeInfoLookup().containsKey("?" + type)));
    }

    public Set<TypeInfo> getTypesImpactCaller(IncrementalManager manager) {
        Set<TypeInfo> typesImpactCaller = new HashSet<>();
        for ( TypeInfo typeInfo : manager.getTypeInfoLookup().values() ) {
            for (Dependency dep : typeInfo.getIncomingDependencies() ) {
                if (dep.isCalleeHasImpact()) {
                    typesImpactCaller.add(typeInfo);
                    break;
                }
            }
        }
        return typesImpactCaller;
    }

    public static class Helper {
        Path tempPath;

        TranspilerTester tester;

        public static Helper create() {
            return new Helper();
        }

        private Helper() {
            try {
                tempPath = createPaths();
            } catch (IOException e) {
                throw new RuntimeException("Unable to create paths");
            }
        }

        private void newTester() {
            tester = newTesterWithDefaults(tempPath.resolve("classes"))
                    .setTempPath(tempPath)
                    .setJavaPackage("test")
                    .setIncremental(true)
                    .setIndirect(false);
        }

        Helper add(String type, String content) {
            if (tester==null) {
                newTester();
            }
            tester.addCompilationUnit(type, content);
            return  this;
        }

        Helper remove(String type) {
            if (tester==null) {
                newTester();
            }
            tester.removeCompilationUnit(type);
            return  this;
        }

        public TranspileResult assertTranspileSucceeds() {
            tester.compile();
            TranspileResult results =   tester.assertTranspileSucceeds();
            tester = null;
            return results;
        }

        public TranspileResult assertTranspileFails() {
            tester.compile();
            TranspileResult results =   tester.assertTranspileFails();
            tester = null;
            return results;
        }

        private Path createPaths() throws IOException {
            Path tempPath = Files.createTempDirectory("tester");
            Files.createDirectories(tempPath.resolve("classes"));

            System.out.println("tempPath\n " + tempPath);
            System.out.println("classesOutPath\n " + tempPath.resolve("classes"));
            return tempPath;
        }
    }
}
