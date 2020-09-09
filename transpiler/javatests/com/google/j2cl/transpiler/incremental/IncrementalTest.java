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
import com.google.j2cl.transpiler.integration.TranspilerTester.TranspileResult;
import com.google.j2cl.incremental.IncrementalManager;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.Test;

import static org.junit.Assert.*;

import static com.google.j2cl.transpiler.integration.TranspilerTester.newTesterWithDefaults;

public class IncrementalTest {

    @Test
    public void test2() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        System.out.println(root);
    }

    private String code(String... code) {
        List<String> content = new ArrayList<>(Arrays.asList(code));
        return Joiner.on('\n').join(content);
    }

    @Test
    public void testDependencyGraphWasBuiltCorrectly() throws IOException {
        Path tempDir = Files.createTempDirectory("tester");
        Path classesOutPath = tempDir.resolve("classes");
        Files.createDirectories(classesOutPath);

        System.out.println("tempDir\n " + tempDir);
        System.out.println("classesOutPath\n " + classesOutPath);

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

        TranspileResult results = newTesterWithDefaults(classesOutPath)
                .setTempPath(tempDir)
                .setJavaPackage("test")
                .addCompilationUnit("Foo1", foo1)
                .addCompilationUnit("Foo2", foo2)
                .addCompilationUnit("Foo3", foo3)
                .addCompilationUnit("Bar", bar)
                .setIncremental(true)
                .setIndirect(false)
                .compile()
                .assertTranspileSucceeds();

        IncrementalManager refMan = results.getIncrementalManager();

        IncrementalManager finalRefMan = refMan;
        List<String> sources = refMan.getSources().stream().map(fileInfo -> finalRefMan.getChangeSet().getPathToUniqueId().get(fileInfo.originalPath())).collect(Collectors.toList());
        assertEquals(4, sources.size());
        assertTrue(sources.contains("?test.Foo1"));
        assertTrue(sources.contains("?test.Foo2"));
        assertTrue(sources.contains("?test.Foo3"));
        assertTrue(sources.contains("?test.Bar"));

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
        Path tempDir = Files.createTempDirectory("tester");
        Path classesOutPath = tempDir.resolve("classes");
        Files.createDirectories(classesOutPath);

        System.out.println("tempDir\n " + tempDir);
        System.out.println("classesOutPath\n " + classesOutPath);

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

        TranspileResult results = newTesterWithDefaults(classesOutPath)
                .setTempPath(tempDir)
                .setJavaPackage("test")
                .addCompilationUnit("Foo1", foo1)
                .addCompilationUnit("Foo2", foo2)
                .addCompilationUnit("Foo3", foo3)
                .addCompilationUnit("Bar", bar)
                .setIncremental(true)
                .setIndirect(false)
                .compile()
                .assertTranspileSucceeds();

        IncrementalManager refMan = results.getIncrementalManager();
        Set<TypeInfo>    impact = getTypesImpactCaller(refMan);
        assertEquals(2, impact.size());
        List<String> result = asListOfString(impact);
        assertEquals(Arrays.asList(new String[] {"Foo1", "Foo2"} ), result);  // Foo3 is not part of this, because has no naming changing JsInterop annot.

        TypeInfo foo3Info = refMan.get("?test.Foo3");
        List<String> callers = asCallerListOfString(foo3Info.getIncomingDependencies());
        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());
        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);  // repeats twice, one for Method one for new Class


        results = newTesterWithDefaults(classesOutPath)
                .setTempPath(tempDir)
                .setJavaPackage("test")
                .addCompilationUnit("Bar", bar2)
                .setIncremental(true)
                .setIndirect(false)
                .compile()
                .assertTranspileSucceeds();

        refMan = results.getIncrementalManager();
        IncrementalManager finalRefMan = refMan;
        List<String> sources = refMan.getSources().stream().map(fileInfo -> finalRefMan.getChangeSet().getPathToUniqueId().get(fileInfo.originalPath())).collect(Collectors.toList());
        assertEquals(1, sources.size());
        assertTrue(sources.contains("?test.Bar"));

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
//
//    @Test
//    public void testInnerTypesConstruction() throws IOException  {
//        // This test exhaustively asserts on the structure of the Dependencies.
//        // Later tests take the same sources, but removes and updates parts
//        TranspileContext ctx            = new TranspileContext();
//        Path             outputLocation = Files.createTempDirectory("outputdir");
//        Path             classesOutPath = Files.createTempDirectory("tester_classesout");
//
//        String foo1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public void method1() { }",
//                           "  public class InnerFoo1 {" +
//                           "    public void innerMethod2() { }",
//                           "    public class InnerInnerFoo1Fum1 {" +
//                           "      public void innerInnerMethod3() { }",
//                           "    }",
//                           "    public class InnerInnerFoo1Fum2 {" +
//                           "      public void innerInnerMethod4() { }",
//                           "    }",
//                           "  }",
//                           "}");
//
//        String bar = code(
//                "public class Bar { private Foo1 foo = new Foo1();",
//                "  public Bar() { }",
//                "  public void method1() {",
//                "      Foo1 foo1 = new Foo1();",
//                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
//                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
//                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
//                "  } ",
//                "}");
//
//        ChangeSet changeSet = new ChangeSet();
//        ctx.setChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1)
//                .addCompilationUnit("Bar", bar)
//                .setTranspileContext(ctx)
//                .assertTranspileSucceeds();
//
//        ReferenceManager refMan = ctx.getReferenceManager();
//        Set<TypeInfo>    impact = refMan.getTypesImpactCaller();
//        assertEquals(1, impact.size());
//        List<String> result = asListOfString(impact);
//        assertEquals(Arrays.asList(new String[] {"Foo1"} ), result);
//
//        TypeInfo barInfo = refMan.get("?test.Bar");
//        assertTrue(barInfo.getIncomingDependencies().isEmpty());
//
//        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
//        Collections.sort(callees);
//        assertEquals(Arrays.asList(new String[] {"Foo1", "InnerFoo1", "InnerInnerFoo1Fum1", "InnerInnerFoo1Fum2"} ), callees);
//
//
//        TypeInfo foo1Info = refMan.get("?test.Foo1");
//        assertTrue(foo1Info.getOutgoingDependencies().isEmpty());
//        List<String> callers = asCallerListOfString(foo1Info.getIncomingDependencies());
//        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);
//
//        assertEquals(1, foo1Info.getInnerTypes().size());
//        TypeInfo foo2Info = foo1Info.getInnerTypes().get(0);
//        assertTrue(foo2Info.getOutgoingDependencies().isEmpty());
//        assertEquals(foo1Info, foo2Info.getEnclosingTypeInfo());
//        callers = asCallerListOfString(foo2Info.getIncomingDependencies());
//        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);
//
//        assertEquals(2, foo2Info.getInnerTypes().size());
//        TypeInfo foo3Info = foo2Info.getInnerTypes().get(0);
//        assertTrue(foo3Info.getOutgoingDependencies().isEmpty());
//        assertEquals(foo2Info, foo3Info.getEnclosingTypeInfo() );
//        assertEquals(foo2Info, foo3Info.getEnclosingTypeInfo() );
//        callers = asCallerListOfString(foo3Info.getIncomingDependencies());
//        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);
//
//        TypeInfo foo4Info = foo2Info.getInnerTypes().get(1);
//        assertTrue(foo4Info.getOutgoingDependencies().isEmpty());
//        assertEquals(foo2Info, foo4Info.getEnclosingTypeInfo() );
//        callers = asCallerListOfString(foo4Info.getIncomingDependencies());
//        assertEquals(Arrays.asList(new String[] {"Bar"} ), callers);
//    }
//
//    @Test
//    public void testInnerTypesWithBarRemoval() throws IOException  {
//        // Further testing testInnerTypesConstruction but with Bar removal
//        TranspileContext ctx            = new TranspileContext();
//        Path             outputLocation = Files.createTempDirectory("outputdir");
//        Path             classesOutPath = Files.createTempDirectory("tester_classesout");
//
//        String foo1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public void method1() { }",
//                           "  public class InnerFoo1 {" +
//                           "    public void innerMethod2() { }",
//                           "    public class InnerInnerFoo1Fum1 {" +
//                           "      public void innerInnerMethod3() { }",
//                           "    }",
//                           "    public class InnerInnerFoo1Fum2 {" +
//                           "      public void innerInnerMethod4() { }",
//                           "    }",
//                           "  }",
//                           "}");
//
//        String bar = code(
//                "public class Bar { private Foo1 foo = new Foo1();",
//                "  public Bar() { }",
//                "  public void method1() {",
//                "      Foo1 foo1 = new Foo1();",
//                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
//                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
//                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
//                "  } ",
//                "}");
//
//        ChangeSet changeSet = new ChangeSet();
//        ctx.setChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1)
//                .addCompilationUnit("Bar", bar)
//                .setTranspileContext(ctx)
//                .assertTranspileSucceeds();
//
//        ReferenceManager refMan = ctx.getReferenceManager();
//
//        // No need to test data structures here, as they are tested in testInnerTypesConstruction
//
//        // Now test Bar removal
//        changeSet.getRemoved().add("test.Bar");
//        remove("test.Bar", outputLocation, classesOutPath);
//        refMan.processChangeSet(changeSet);
//
//        TypeInfo foo1Info = refMan.get("?test.Foo1");
//        TypeInfo foo2Info = refMan.get("?test.Foo1$InnerFoo1");
//        TypeInfo foo3Info = refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1");
//        TypeInfo foo4Info = refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2");
//
//        assertTrue(asCallerListOfString(foo1Info.getIncomingDependencies()).isEmpty());
//        assertTrue(asCallerListOfString(foo2Info.getIncomingDependencies()).isEmpty());
//        assertTrue(asCallerListOfString(foo3Info.getIncomingDependencies()).isEmpty());
//        assertTrue(asCallerListOfString(foo4Info.getIncomingDependencies()).isEmpty());
//    }
//
//    @Test
//    public void testInnerTypesWithFooRemoval() throws IOException  {
//        // Further testing testInnerTypesConstruction but with Bar update and Foo removal
//        TranspileContext ctx            = new TranspileContext();
//        Path             outputLocation = Files.createTempDirectory("outputdir");
//        Path             classesOutPath = Files.createTempDirectory("tester_classesout");
//
//        String foo1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public void method1() { }",
//                           "  public class InnerFoo1 {" +
//                           "    public void innerMethod2() { }",
//                           "    public class InnerInnerFoo1Fum1 {" +
//                           "      public void innerInnerMethod3() { }",
//                           "    }",
//                           "    public class InnerInnerFoo1Fum2 {" +
//                           "      public void innerInnerMethod4() { }",
//                           "    }",
//                           "  }",
//                           "}");
//
//        String bar = code(
//                "public class Bar { private Foo1 foo = new Foo1();",
//                "  public Bar() { }",
//                "  public void method1() {",
//                "      Foo1 foo1 = new Foo1();",
//                "      Foo1.InnerFoo1 foo2 = foo1.new InnerFoo1();",
//                "      Foo1.InnerFoo1.InnerInnerFoo1Fum1 foo3 = foo2.new InnerInnerFoo1Fum1();",
//                "      Foo1.InnerFoo1.InnerInnerFoo1Fum2 foo4 = foo2.new InnerInnerFoo1Fum2();",
//                "  } ",
//                "}");
//
//        String bar2 = code(
//                "public class Bar {",
//                "  public Bar() { }",
//                "  public void method1() {",
//                "  } ",
//                "}");
//
//        ChangeSet changeSet = new ChangeSet();
//        ctx.setChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1)
//                .addCompilationUnit("Bar", bar)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileSucceeds();
//
//        ReferenceManager refMan = ctx.getReferenceManager();
//        Set<TypeInfo>    impact = refMan.getTypesImpactCaller();
//        assertEquals(1, impact.size());
//        List<String> result = asListOfString(impact);
//        assertEquals(Arrays.asList(new String[] {"Foo1"} ), result);
//
//        TypeInfo barInfo = refMan.get("?test.Bar");
//
//        List<String> callees = asCalleeListOfString(barInfo.getOutgoingDependencies());
//        Collections.sort(callees);
//        assertEquals(Arrays.asList(new String[] {"Foo1", "InnerFoo1", "InnerInnerFoo1Fum1", "InnerInnerFoo1Fum2"} ), callees);
//
//        assertNotNull(refMan.get("?test.Foo1"));
//        assertNotNull(refMan.get("?test.Foo1$InnerFoo1"));
//        assertNotNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1"));
//        assertNotNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2"));
//
//        // Now test Foo removal
//        changeSet.getRemoved().add("test.Foo1");
//        remove("test.Foo1", outputLocation, classesOutPath);
//        changeSet.getUpdated().add("test.Bar");
//        refMan.processChangeSet(changeSet);
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Bar", bar2)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileSucceeds();
//
//        assertEquals(0, impact.size());
//
//        assertTrue(barInfo.getIncomingDependencies().isEmpty());
//        assertTrue(barInfo.getOutgoingDependencies().isEmpty());
//
//        assertNull(refMan.get("?test.Foo1"));
//        assertNull(refMan.get("?test.Foo1$InnerFoo1"));
//        assertNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1"));
//        assertNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2"));
//    }
//
//    @Test
//    public void testIncorrectInnerTypeRemoval() throws IOException  {
//        // Root types should be ignored. We assume it's coming at some point, so just ignore and warn.
//        TranspileContext ctx            = new TranspileContext();
//        Path             outputLocation = Files.createTempDirectory("outputdir");
//        Path             classesOutPath = Files.createTempDirectory("tester_classesout");
//
//        String foo1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public void method1() { }",
//                           "  public class InnerFoo1 {" +
//                           "    public void innerMethod2() { }",
//                           "    public class InnerInnerFoo1Fum1 {" +
//                           "      public void innerInnerMethod3() { }",
//                           "    }",
//                           "    public class InnerInnerFoo1Fum2 {" +
//                           "      public void innerInnerMethod4() { }",
//                           "    }",
//                           "  }",
//                           "}");
//
//        ChangeSet changeSet = new ChangeSet();
//        ctx.setChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1)
//                .setTranspileContext(ctx)
//                .assertTranspileSucceeds();
//
//        ReferenceManager refMan = ctx.getReferenceManager();
//
//        assertNotNull(refMan.get("?test.Foo1"));
//        assertNotNull(refMan.get("?test.Foo1$InnerFoo1"));
//        assertNotNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum1"));
//        assertNotNull(refMan.get("?test.Foo1$InnerFoo1$InnerInnerFoo1Fum2"));
//
//        try {
//            // Now test inner Foo removal
//            changeSet.getRemoved().add("test.Foo1$InnerFoo1");
//            refMan.processChangeSet(changeSet);
//            fail("Cannot directly remove inner classes, they should be excluded via filters before this point.");
//        } catch (IllegalStateException e) {
//
//        }
//    }
//
//    @Test
//    public void testContinuationAfterError() throws IOException {
//        TranspileContext ctx            = new TranspileContext();
//        Path             outputLocation = Files.createTempDirectory("outputdir");
//        Path             classesOutPath = Files.createTempDirectory("tester_classesout");
//
//        String foo1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
//                           "  public class InnerFoo {}",
//                           "}");
//
//        String foo1_1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public int i = 0; private int j = 1; public void getJ() { return j;}",
//                           "}");
//
//        String foo2 = code("import jsinterop.annotations.JsMethod;",
//                           "public class Foo2 {",
//                           "  @JsMethod(name =\"goodBye\")",
//                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
//                           "}");
//
//        String foo2_1 = code("import jsinterop.annotations.JsMethod;",
//                           "public class Foo2 {",
//                           "  @JsMethod(name =\"goodBye\")",
//                           "  public String helloWorld(int i, String s) { return 5; } ",
//                           "}");
//
//        String foo3 = code("public class Foo3 {",
//                           "  public String m1(int i, String s) { return \"xxx\"; } ",
//                           "}");
//
//        String bar = code(
//                "public class Bar { private Foo1 foo = new Foo1();",
//                "  public Bar() { }",
//                "  public void method1() {",
//                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
//                "      Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
//                "  } ",
//                "}");
//
//        String bar2 = code(
//                "public class Bar { private Foo1 foo = new Foo1();",
//                "  public Bar() { }",
//                "  public void method1() {",
//                "      Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
//                "      Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
//                "      Foo3 foo3 = new Foo3();",
//                "  } ",
//                "}");
//
//        ChangeSet changeSet = new ChangeSet();
//        ctx.setChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1)
//                .addCompilationUnit("Foo2", foo2)
//                .addCompilationUnit("Bar", bar)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileSucceeds();
//
//        ReferenceManager refMan = ctx.getReferenceManager();
//        changeSet.getUpdated().add("test.Foo1"); // this will be broken.
//        refMan.processChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1_1)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileFails();
//
//        changeSet.getUpdated().add("test.Foo2"); // this will be broken.
//        changeSet.getUpdated().add("test.Bar"); // this will fail due to Foo1 and Foo2
//        changeSet.getAdded().add("test.Foo3"); // this will be ok.
//        refMan.processChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo2", foo2_1)
//                .addCompilationUnit("Foo3", foo3)
//                .addCompilationUnit("Bar", bar2)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileFails();
//
//        refMan.processChangeSet(changeSet);
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1)
//                .addCompilationUnit("Foo2", foo2)
//                .addCompilationUnit("Foo3", foo3)
//                .addCompilationUnit("Bar", bar2)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileSucceeds();
//    }
//
//    @Test
//    public void testTypeDescriptorIsUpdated() throws IOException {
//        // Make sure the DeclaredTypeDescriptor refererence is updated on each transpile of that class
//
//        TranspileContext ctx            = new TranspileContext();
//        Path             outputLocation = Files.createTempDirectory("outputdir");
//        Path             classesOutPath = Files.createTempDirectory("tester_classesout");
//
//        String foo1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
//                           "  public class InnerFoo {}",
//                           "}");
//
//        String foo1_1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public int i = 5; private int j = 1; public int getJ() { return j;}",
//                           "  public class InnerFoo {}",
//                           "}");
//
//        ChangeSet changeSet = new ChangeSet();
//        ctx.setChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileSucceeds();
//
//        ReferenceManager refMan = ctx.getReferenceManager();
//        TypeInfo typeInfo1 = refMan.get("?test.Foo1");
//        DeclaredTypeDescriptor typeDeclr1 = typeInfo1.getType();
//
//
//        changeSet.getUpdated().add("test.Foo1");
//        refMan.processChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1_1)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileSucceeds();
//
//        changeSet.getUpdated().add("test.Foo1");
//        refMan.processChangeSet(changeSet);
//
//        TypeInfo typeInfo2 = refMan.get("?test.Foo1");
//        DeclaredTypeDescriptor typeDeclr2 = typeInfo1.getType();
//
//        assertSame(typeInfo1, typeInfo2);
//        assertNotSame(typeDeclr1, typeDeclr2);
//    }
//
//    @Test
//    public void testImpactUpdated() throws IOException {
//        TranspileContext ctx            = new TranspileContext();
//        Path             outputLocation = Files.createTempDirectory("outputdir");
//        Path             classesOutPath = Files.createTempDirectory("tester_classesout");
//
//        String foo1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
//                           "  public class InnerFoo {}",
//                           "}");
//
//        String foo2 = code("import jsinterop.annotations.JsMethod;",
//                           "public class Foo2 {",
//                           "  @JsMethod(name =\"goodBye\")",
//                           "  public String helloWorld(int i, String s) { return \"xxx\"; } ",
//                           "}");
//
//        String foo3 = code("public class Foo3 {",
//                           "  public String m1(int i, String s) {",
//                           "    Bar bar = new Bar();",
//                           "    return \"xxx\";",
//                           "  } ",
//                           "}");
//
//        String bar = code(
//                "public class Bar { private Foo1 foo = new Foo1();",
//                "  public Bar() { }",
//                "  public void method1() {",
//                "    Foo2 foo2 = new Foo2(); String str = foo2.helloWorld(10, null);",
//                "    Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
//                "  } ",
//                "}");
//
//        ChangeSet changeSet = new ChangeSet();
//        ctx.setChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1)
//                .addCompilationUnit("Foo2", foo2)
//                .addCompilationUnit("Foo3", foo3)
//                .addCompilationUnit("Bar", bar)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileSucceeds();
//
//        ReferenceManager refMan = ctx.getReferenceManager();
//        TypeInfo foo1Info = refMan.get("?test.Foo1");
//        TypeInfo foo2Info = refMan.get("?test.Foo2");
//        TypeInfo foo3Info = refMan.get("?test.Foo3");
//        TypeInfo barInfo = refMan.get("?test.Bar");
//
//        List<String> impacted = refMan.getTypesImpactCaller().stream().map( TypeInfo::getUniqueId).collect(Collectors.toList());
//        assertEquals(2, impacted.size());
//        assertTrue(impacted.contains("?test.Foo1"));
//        assertTrue(impacted.contains("?test.Foo2"));
//
//        // Foo3 and Bar are connected, but neither is in the impacted, as no JsInterop impact
//        assertEquals(0, foo3Info.getIncomingDependencies().size());
//        assertEquals(1, foo3Info.getOutgoingDependencies().size());
//
//        // no dependencies are marked as having an impact
//        List<Dependency> deps = foo3Info.getOutgoingDependencies().stream().filter(d -> d.isCalleeHasImpact()).collect(Collectors.toList());
//        assertEquals(0, deps.size());
//
//        // Foo1 has JsType so it impacts the caller Bar
//        assertEquals(4, foo1Info.getIncomingDependencies().size());
//        assertEquals(0, foo1Info.getOutgoingDependencies().size());
//        deps = foo1Info.getIncomingDependencies().stream().filter(d -> d.isCalleeHasImpact()).collect(Collectors.toList());
//        assertEquals(2, deps.size());
//
//        // Foo2 has JsMethod so it impacts the caller Bar
//        assertEquals(2, foo2Info.getIncomingDependencies().size());
//        assertEquals(0, foo2Info.getOutgoingDependencies().size());
//        deps = foo2Info.getIncomingDependencies().stream().filter(d -> d.isCalleeHasImpact()).collect(Collectors.toList());
//        assertEquals(1, deps.size());
//
//        // Check Bar, should be combined inverse of Foo1 and Foo2
//        assertEquals(1, barInfo.getIncomingDependencies().size());
//        assertFalse(barInfo.getIncomingDependencies().get(0).isCalleeHasImpact());
//        assertEquals(6, barInfo.getOutgoingDependencies().size());
//        deps = barInfo.getOutgoingDependencies().stream().filter(d -> d.isCalleeHasImpact()).collect(Collectors.toList());
//        assertEquals(3, deps.size());
//
//        // now check ChangeSet
//        changeSet.getUpdated().add("test.Foo1"); // impacts Bar
//        refMan.processChangeSet(changeSet);
//        assertEquals(1, changeSet.getImpacted().size());
//        assertEquals("?test.Bar", changeSet.getImpacted().stream().collect(Collectors.toList()).get(0));
//
//        // now check ChangeSet
//        changeSet.clear();
//        changeSet.getUpdated().add("test.Foo3"); // has no impact
//        refMan.processChangeSet(changeSet);
//        assertEquals(0, changeSet.getImpacted().size());
//    }
//
//    @Test
//    public void testUpdatedItemNotAddedToImpacted() throws IOException {
//        TranspileContext ctx            = new TranspileContext();
//        Path             outputLocation = Files.createTempDirectory("outputdir");
//        Path             classesOutPath = Files.createTempDirectory("tester_classesout");
//
//        String foo1 = code("import jsinterop.annotations.JsType;",
//                           "@JsType(name = \"Faz\")",
//                           "public class Foo1 {",
//                           "  public int i = 0; private int j = 1; public int getJ() { return j;}",
//                           "  public class InnerFoo {}",
//                           "}");
//
//        String bar = code(
//                "public class Bar { private Foo1 foo = new Foo1();",
//                "  public Bar() { }",
//                "  public void method1() {",
//                "    Foo1 foo1 = new Foo1(); int i = foo1.i; int j = foo1.getJ();",
//                "  } ",
//                "}");
//
//        ChangeSet changeSet = new ChangeSet();
//        ctx.setChangeSet(changeSet);
//
//        newTesterWithDefaults(classesOutPath)
//                .setOutputPath(outputLocation)
//                .setJavaPackage("test")
//                .addCompilationUnit("Foo1", foo1)
//                .addCompilationUnit("Bar", bar)
//                .setTranspileContext(ctx)
//                .compile()
//                .assertTranspileSucceeds();
//
//        ReferenceManager refMan = ctx.getReferenceManager();
//        TypeInfo foo1Info = refMan.get("?test.Foo1");
//        TypeInfo barInfo = refMan.get("?test.Bar");
//
//        // now check ChangeSet, Bar is in imacted set
//        changeSet.getUpdated().add("test.Foo1"); // impacts Bar
//        refMan.processChangeSet(changeSet);
//        assertEquals(1, changeSet.getImpacted().size());
//        assertEquals("?test.Bar", changeSet.getImpacted().stream().collect(Collectors.toList()).get(0));
//
//        // now check ChangeSet, Bar should only be in Updated set
//        changeSet.clear();
//        changeSet.getUpdated().add("test.Foo1"); // impacts Bar, But Bar is in Changeset, so should not appear in impacted
//        changeSet.getUpdated().add("test.Bar"); // impacts Bar
//        refMan.processChangeSet(changeSet);
//        assertEquals(0, changeSet.getImpacted().size());
//    }
//
//
//    public boolean builtInType(DeclaredTypeDescriptor referenceType) {
//        String name = referenceType.getQualifiedSourceName();
//        switch (name) {
//            case "java.io.Serializable":
//            case "java.lang.Boolean":
//            case "java.lang.Byte":
//            case "java.lang.Character":
//            case "java.lang.Double":
//            case "java.lang.Float":
//            case "java.lang.Integer":
//            case "java.lang.Long":
//            case "java.lang.Short":
//            case "java.lang.String":
//            case "java.lang.Void":
//            case "java.lang.Class":
//            case "java.lang.Object":
//            case "java.lang.Throwable":
//            case "java.lang.NullPointerException":
//            case "java.lang.Number":
//            case "java.lang.Comparable":
//            case "java.lang.CharSequence":
//            case "java.lang.Cloneable":
//            case "java.lang.Enum":
//            case "java.lang.Runnable":
//            case "javaemul.internal.InternalPreconditions":
//                return true;
//            default:
//        }
//        if (name.startsWith("$synthetic.") || name.startsWith("java")) {
//            return true;
//        }
//
//        return false;
//    }
//
    private List<String> asListOfString(Set<TypeInfo> impact) {
        return impact.stream().map(t -> t.getType().getSimpleSourceName()).collect(Collectors.toList());
    }

    private List<String> asCalleeListOfString(List<Dependency> deps) {
        return deps.stream().map(t -> t.getCallee().getType().getSimpleSourceName()).collect(Collectors.toList());
    }

    private List<String> asCallerListOfString(List<Dependency> deps) {
        return deps.stream().map(t -> t.getCaller().getType().getSimpleSourceName()).collect(Collectors.toList());
    }
//
//
//    private void remove(TypeInfo typeInfo, Path outputLocation, Path classesOutPath) {
//        for (TypeInfo child : typeInfo.getInnerTypes()) {
//            remove(child, outputLocation, classesOutPath);
//        }
//        remove(typeInfo.getType().getQualifiedSourceName(), outputLocation, classesOutPath);
//    }
//
//    private void remove(String qualifiedName, Path outputLocation, Path classesOutPath) {
//        for ( String ext : new String[] {".java", ".impl.java.js", ".java.js", ".js.map"}) {
//            String sourcePath         = qualifiedName.replace(".", "/") + ext;
//            Path   absoluteSourcePath = outputLocation.resolve(sourcePath);
//            assertTrue(Files.exists(absoluteSourcePath));
//            try {
//                Files.delete(absoluteSourcePath);
//            } catch (IOException e) {
//                fail();
//            }
//        }
//
//        String binaryPath = qualifiedName.replace(".", "/") + ".class";
//        Path absoluteBinaryPath = classesOutPath.resolve(binaryPath);
//        assertTrue(Files.exists(absoluteBinaryPath));
//        try {
//            Files.delete(absoluteBinaryPath);
//        } catch (IOException e) {
//            fail();
//        }
//    }
}
