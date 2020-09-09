/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.integration;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.j2cl.transpiler.J2clCommandLineRunner.getPathEntries;

import com.google.common.base.Joiner;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.MoreFiles;
import com.google.common.truth.Correspondence;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.j2cl.common.FrontendUtils;
import com.google.j2cl.common.J2clUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.frontend.Frontend;
import com.google.j2cl.incremental.IncrementalManager;
import com.google.j2cl.transpiler.J2clCommandLineRunner;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.j2cl.transpiler.J2clTranspilerOptions;
import junit.framework.Assert;

/** Apis for end to end tests on the transpiler. */
public class TranspilerTester {
  /** Creates a new transpiler tester. */
  public static TranspilerTester newTester() {
    return new TranspilerTester();
  }

  /**
   * Creates a new transpiler tester initialized with the defaults (e.g. location for the JRE, etc)
   * @param classesOutPath
   */
  public static TranspilerTester newTesterWithDefaults() {
    return newTester()
            .setJavaPackage("test")
            .setClassPath(
                    System.getProperty("TEST_PATH_PREFIX", "") + "transpiler/javatests/com/google/j2cl/transpiler/integration/jre_bundle_deploy.jar");
  }

  /**
   * Creates a new transpiler tester initialized with the defaults (e.g. location for the JRE, etc)
   * @param classesOutPath
   */
  public static TranspilerTester newTesterWithDefaults(Path classesOutPath) {
    return newTester()
        .setJavaPackage("test")
        .setClassesOutPath(classesOutPath)
        .setClassPath(
            System.getProperty("TEST_PATH_PREFIX", "") + "transpiler/javatests/com/google/j2cl/transpiler/integration/jre_bundle_deploy.jar");
  }

  public static class File {
    private Path filePath;
    private String content;

    public File(Path filePath, String content) {
      this.filePath = filePath;
      this.content = content;
    }

    public Path getFilePath() {
      return filePath;
    }

    public void createFileIn(Path basePath) {
      try {
        Files.write(basePath.resolve(filePath), content.getBytes(Charset.forName("UTF-8")));
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    public boolean isNativeJsFile() {
      return filePath.toString().endsWith(".native.js");
    }

    public boolean isJavaSourceFile() {
      return filePath.toString().endsWith(".java");
    }

    public boolean isSrcJar() {
      return filePath.toString().endsWith(".srcjar");
    }
  }

  private List<File>         files = new ArrayList<>();
  private List<String>       args = new ArrayList<>();
  private String             temporaryDirectoryPrefix = "transpile_tester";
  private String             packageName = "";
  private Path outputPath;
  private Path tempPath;
  private Path classesOutPath;
  private String             classPath;
  private boolean            indirect = true;
  private boolean            incremental = false;

  public TranspilerTester setIndirect(boolean indirect) {
    this.indirect = indirect;
    return this;
  }

  public TranspilerTester setIncremental(boolean incremental) {
    this.incremental = incremental;
    return this;
  }

  public TranspilerTester addCompilationUnit(String compilationUnitName, String... code) {
    List<String> content = new ArrayList<>(Arrays.asList(code));
    if (!packageName.isEmpty()) {
      content.add(0, "package " + packageName + ";");
    }
    return addPath(
        getPackageRelativePath(compilationUnitName + ".java"), Joiner.on('\n').join(content));
  }

  public TranspilerTester removeCompilationUnit(String compilationUnitName) {
    Path path = getPackageRelativePath(compilationUnitName + ".java");
    Path inputPath = tempPath.resolve("input");
    try {
      Files.delete(inputPath.resolve(path));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return this;
  }

  public TranspilerTester addNativeFile(String compilationUnitName, String... code) {
    return addPath(
        getPackageRelativePath(compilationUnitName + ".native.js"), Joiner.on('\n').join(code));
  }

  public TranspilerTester addFile(String filename, String content) {
    return addPath(Paths.get(filename), content);
  }

  private TranspilerTester addPath(Path filePath, String content) {
    this.files.add(new File(filePath, content));
    return this;
  }

  public Path getClassesOutPath() {
    return classesOutPath;
  }

  public TranspilerTester setClassPath(String path) {
    if ( classesOutPath != null ) {
      classPath = toTestPath(path) + ":" + toTestPath(classesOutPath.toAbsolutePath().toString());
    } else {
      classPath = toTestPath(path);
    }

    return this.addArgs("-cp", classPath);
  }

  public TranspilerTester setNativeSourcePath(String path) {
    return this.addArgs("-nativesourcepath", toTestPath(path));
  }

  public TranspilerTester addSourcePath(String path) {
    return this.addArgs(toTestPath(path));
  }

  private static String toTestPath(String path) {
    return path;
  }

  public TranspilerTester setArgs(String... args) {
    return setArgs(Arrays.asList(args));
  }

  public TranspilerTester setArgs(Collection<String> args) {
    this.args = new ArrayList<>(args);
    return this;
  }

  public TranspilerTester addArgs(String... args) {
    return addArgs(Arrays.asList(args));
  }

  public TranspilerTester addArgs(Collection<String> args) {
    this.args.addAll(args);
    return this;
  }

  public TranspilerTester setJavaPackage(String packageName) {
    this.packageName = packageName;
    return this;
  }

  public TranspilerTester setOutputPath(Path outputPath) {
    this.outputPath = outputPath;
    return this;
  }

  public TranspilerTester setTempPath(Path tempPath) {
    this.tempPath = tempPath;
    return this;
  }

  public TranspilerTester setClassesOutPath(Path classesOutPath) {
    this.classesOutPath = classesOutPath;
    return this;
  }
  public TranspileResult assertTranspileSucceeds() {
    return transpile().assertNoErrors();
  }

  public TranspileResult assertTranspileFails() {
    return transpile().assertHasErrors();
  }

  /** A bundle of data recording the results of a transpile operation. */
  public static class TranspileResult {
    private final Problems problems;
    private final Path               outputPath;
    private final IncrementalManager incrementalManager;

    public TranspileResult(Problems problems, Path outputPath) {
      this(problems, outputPath, null);
    }

    public TranspileResult(Problems problems, Path outputPath, IncrementalManager incrementalManager) {
      this.problems = problems;
      this.outputPath = outputPath;
      this.incrementalManager = incrementalManager;
    }

    public Problems getProblems() {
      return problems;
    }

    public Path getOutputPath() {
      return outputPath;
    }

    public List<String> getOutputSource(String outputFile) throws IOException {
      Path outputFilePath = outputPath.resolve(outputFile);
      assertThat(outputFilePath.toFile().exists()).isTrue();
      return Files.readAllLines(outputFilePath);
    }

    public IncrementalManager getIncrementalManager() {
      return incrementalManager;
    }

    public TranspileResult assertNoWarnings() {
      return assertWarningsWithoutSourcePosition();
    }

    public TranspileResult assertWarningsWithoutSourcePosition(String... expectedWarnings) {
      assertThat(getProblems().getWarnings())
          .comparingElementsUsing(ERROR_WITHOUT_SOURCE_POSITION_COMPARATOR)
          .containsExactlyElementsIn(Arrays.asList(expectedWarnings));
      return this;
    }

    public TranspileResult assertNoErrors() {
      assertThat(getProblems().getErrors()).isEmpty();
      return this;
    }

    public TranspileResult assertHasErrors() {
      assertThat(getProblems().getErrors()).isNotEmpty();
      return this;
    }

    public TranspileResult assertErrorsWithoutSourcePosition(String... expectedErrors) {
      assertThat(getProblems().getErrors())
          .comparingElementsUsing(ERROR_WITHOUT_SOURCE_POSITION_COMPARATOR)
          .containsExactlyElementsIn(Arrays.asList(expectedErrors));
      return this;
    }

    public TranspileResult assertErrorsWithSourcePosition(String... expectedErrors) {
      assertThat(getProblems().getErrors())
          .containsExactlyElementsIn(Arrays.asList(expectedErrors));
      return this;
    }

    public TranspileResult assertLastMessage(String expectedMessage) {
      List<String> allMsgs = getProblems().getMessages();
      String lastMessage = Iterables.getLast(allMsgs, "");
      assertThat(lastMessage).contains(expectedMessage);
      return this;
    }

    public TranspileResult assertErrorsContainsSnippets(String... snippets) {
      assertThat(getProblems().getErrors())
          .comparingElementsUsing(Correspondence.from(String::contains, "contained within"))
          .containsAtLeastElementsIn(Arrays.asList(snippets));
      return this;
    }

    public TranspileResult assertOutputStreamContainsSnippets(String... snippets) {
      String output =
          J2clUtils.streamToString(stream -> getProblems().reportAndGetExitCode(stream));
      Arrays.stream(snippets)
          .forEach(snippet -> assertWithMessage("Output").that(output).contains(snippet));
      return this;
    }

    public TranspileResult assertOutputFilesExist(String... fileNames) {
      Arrays.stream(fileNames)
          .forEach(fileName -> Assert.assertTrue(Files.exists(outputPath.resolve(fileName))));
      return this;
    }

    public TranspileResult assertOutputFilesDoNotExist(String... fileNames) {
      Arrays.stream(fileNames)
          .forEach(fileName -> Assert.assertFalse(Files.exists(outputPath.resolve(fileName))));
      return this;
    }

    public TranspileResult assertOutputFilesAreSame(TranspileResult other) throws IOException {
      List<Path> actualPaths =
          ImmutableList.copyOf(MoreFiles.fileTraverser().depthFirstPreOrder(outputPath));
      List<Path> expectedPaths =
          ImmutableList.copyOf(MoreFiles.fileTraverser().depthFirstPreOrder(other.outputPath));

      // Compare simple names.
      assertThat(toFileNames(actualPaths))
          .containsExactlyElementsIn(toFileNames(expectedPaths))
          .inOrder();

      // Compare file contents.
      for (int i = 0; i < expectedPaths.size(); i++) {
        Path expectedPath = expectedPaths.get(i);
        Path actualPath = actualPaths.get(i);
        if (Files.isDirectory(expectedPath)) {
          assertThat(Files.isDirectory(actualPath)).isTrue();
        } else {
          assertThat(Files.readAllLines(actualPath))
              .containsExactlyElementsIn(Files.readAllLines(expectedPath))
              .inOrder();
        }
      }

      return this;
    }

    private static List<Path> toFileNames(List<Path> original) {
      return original.stream().map(Path::getFileName).collect(ImmutableList.toImmutableList());
    }

    private static final Pattern messagePattern =
        Pattern.compile("(?:(?:Error)|(?:Warning))(?::[\\w.]+:\\d+)?: (?<message>.*)");

    private static final Correspondence<String, String> ERROR_WITHOUT_SOURCE_POSITION_COMPARATOR =
        Correspondence.from(TranspileResult::compare, "contained within");

    private static boolean compare(String actual, String expected) {
      Matcher matcher = messagePattern.matcher(actual);
      checkState(matcher.matches());
      return matcher.group("message").equals(expected);
    }
  }

  private static TranspileResult invokeTranspiler(Iterable<String> args, Path outputPath) {
    try {
      return new TranspileResult(transpile(args), outputPath);
    } catch (Exception e) {
      e.printStackTrace();
      Problems problems = new Problems();
      problems.error("%s", e.toString());
      return new TranspileResult(problems, outputPath);
    }
  }

  private static Problems transpile(Iterable<String> args) throws Exception {
    // J2clCommandLineRunner.run is hidden since we don't want it to be used as an entry point. As a
    // result we use reflection here to invoke it.
    Method transpileMethod =
        J2clCommandLineRunner.class.getDeclaredMethod("runForTest", String[].class);
    transpileMethod.setAccessible(true);
    Problems problems = (Problems) transpileMethod.invoke(null, (Object) Iterables.toArray(args, String.class));
    return problems;
  }

  private TranspileResult transpile() {
    if (indirect) {
      return transpileIndirect();
    } else {
      try {
        return transpileDirect();
      } catch (Exception e) {
        e.printStackTrace();
        Problems problems = new Problems();
        problems.error("%s", e.toString());
        return new TranspileResult(problems, outputPath);
      }
    }
  }

  private TranspileResult transpileIndirect() {
    try {
      Path tempDir = (this.tempPath != null) ? this.tempPath : Files.createTempDirectory(temporaryDirectoryPrefix);

      createOutPath(tempDir);

      ImmutableList.Builder<String> commandLineArgsBuilder =
          ImmutableList.<String>builder()
              // Output dir
              .add("-d", outputPath.toAbsolutePath().toString());

      if (!files.isEmpty()) {
        // 1. Create an input directory
        Path inputPath = tempDir.resolve("input");

        generateFiles(inputPath);

        Path packagePath = inputPath.resolve(packageName);
        ImmutableList<String> paths = getAllFiles(packagePath).stream()
                                                              .map(file -> file.getFilePath().toAbsolutePath().toString())
                                                              .collect(toImmutableList());

        // 3. Add Java source files and srcjar files to command line.
        commandLineArgsBuilder.addAll(paths);

        // 4. Create a source zip containing the native.js sources.
        List<File> nativeSources =
            files.stream().filter(File::isNativeJsFile).collect(toImmutableList());
        if (!nativeSources.isEmpty()) {
          Path nativeZipPath = createNativeZipFile(inputPath, nativeSources, "nativefiles.zip");
          commandLineArgsBuilder.add("-nativesourcepath", nativeZipPath.toString());
        }
      }

      // Passthru explicitly defined args
      commandLineArgsBuilder.addAll(args);

      return invokeTranspiler(commandLineArgsBuilder.build(), outputPath);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private void createOutPath(Path tempDir) throws IOException {
    if (outputPath == null) {
      outputPath = tempDir.resolve("output");
      Files.createDirectories(outputPath);
    }
  }

  private List<String> generateFiles(Path inputPath) throws IOException {
    Files.createDirectories(inputPath);

    checkState(!packageName.contains(".") && !packageName.contains("/"));
    Path packagePath = inputPath.resolve(packageName);
    Files.createDirectories(packagePath);

    // 2. Create all new declared files on disk
    files.forEach(file -> file.createFileIn(inputPath));

    return getAllFiles(packagePath).stream()
                                   .filter(Predicates.or(File::isJavaSourceFile, File::isSrcJar))
                                   .map(file -> inputPath.resolve(file.getFilePath()))
                                   .map(Path::toAbsolutePath)
                                   .map(Path::toString)
                                   .collect(toImmutableList());
  }

//  private J2clTranspilerOptions createOptions(List<String> files, String output) {
//    Problems problems = new Problems();
//
//    return J2clTranspilerOptions.newBuilder()
//                                .setSources(
//                                        FrontendUtils.getAllSources(files, problems)
//                                                     .filter(p -> p.sourcePath().endsWith(".java"))
//                                                     .collect(ImmutableList.toImmutableList()))
//                                .setNativeSources(
//                                        FrontendUtils.getAllSources(getPathEntries(""), problems)
//                                                     .filter(p -> p.sourcePath().endsWith(".native.js"))
//                                                     .collect(ImmutableList.toImmutableList()))
//                                .setClasspaths(getPathEntries(this.classPath))
//                                .setOutput(
//                                        output.endsWith(".zip")
//                                        ? getZipOutput(output, problems)
//                                        : getDirOutput(output, problems))
//                                .setEmitReadableSourceMap(false)
//                                .setEmitReadableLibraryInfo(false)
//                                .setGenerateKytheIndexingMetadata(false)
//                                .setFrontend(Frontend.JAVAC)
//                                .build();
//  }

  private TranspileResult transpileDirect() throws Exception  {
    Path tempDir = (this.tempPath != null) ? this.tempPath : Files.createTempDirectory(temporaryDirectoryPrefix);

    createOutPath(tempDir);

    J2clTranspilerOptions.Builder builder = J2clTranspilerOptions.newBuilder();

    //if (!files.isEmpty()) {
      Problems problems = new Problems();
      // 1. Create an input directory
      Path inputPath = tempDir.resolve("input");

      generateFiles(inputPath);

      Path packagePath = inputPath.resolve(packageName);
      ImmutableList<String> paths = getAllFiles(packagePath).stream()
                                                            .map(file -> file.getFilePath().toAbsolutePath().toString())
                                                            .collect(toImmutableList());

      builder.setSources(FrontendUtils.getAllSources(paths, problems)
                                      .filter(p -> p.sourcePath().endsWith(".java"))
                                      .collect(ImmutableList.toImmutableList()));

      // 4. Create a source zip containing the native.js sources.
//      List<File> nativeSources =
//              files.stream().filter(File::isNativeJsFile).collect(toImmutableList());
//      if (!nativeSources.isEmpty()) {
//        Path nativeZipPath = createNativeZipFile(inputPath, nativeSources, "nativefiles.zip");
//        builder.setNativeSources(
//                FrontendUtils.getAllSources(getPathEntries(nativeZipPath.toAbsolutePath().toString()), problems)
//                             .filter(p -> p.sourcePath().endsWith(".native.js"))
//                             .collect(ImmutableList.toImmutableList()));
//      }
      builder.setNativeSources(
              FrontendUtils.getAllSources(getPathEntries(""), problems)
                           .filter(p -> p.sourcePath().endsWith(".native.js"))
                           .collect(ImmutableList.toImmutableList()));

      builder.setClasspaths(getPathEntries(this.classPath));
      builder.setOutput(this.outputPath);
      builder.setEmitReadableSourceMap(false);
      builder.setEmitReadableLibraryInfo(false);
      builder.setGenerateKytheIndexingMetadata(false);
      builder.setFrontend(Frontend.JAVAC);
      builder.setIncremental(this.incremental);
    //}

    J2clTranspilerOptions options = builder.build();
    try {
      TranspileResult results = transpileDirect(options, outputPath);
      return results;
    } catch (Exception e) {
      e.printStackTrace();
      problems = new Problems();
      problems.error("%s", e.toString());
      return new TranspileResult(problems, outputPath);
    }
  }

  private static TranspileResult transpileDirect(J2clTranspilerOptions options, Path outputPath) throws Exception {
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    Future<TranspileResult> result = executorService.submit(() -> invokeDirect(options, outputPath));
    executorService.shutdown();

    try {
      return Uninterruptibles.getUninterruptibly(result);
    } catch (ExecutionException e) {
      // Try unwrapping the cause...
      Throwables.throwIfUnchecked(e.getCause());
      throw new AssertionError(e.getCause());
    }
  }

  private static TranspileResult invokeDirect(J2clTranspilerOptions options, Path outputPath) throws NoSuchMethodException, InstantiationException, IllegalAccessException, java.lang.reflect.InvocationTargetException {
    Constructor<J2clTranspiler> constructor =  J2clTranspiler.class.getDeclaredConstructor(J2clTranspilerOptions.class);
    constructor.setAccessible(true);
    J2clTranspiler j2cl = constructor.newInstance(options);

    Method transpileMethod =
            J2clTranspiler.class.getDeclaredMethod("transpileImpl");
    transpileMethod.setAccessible(true);

    Problems problems = (Problems) transpileMethod.invoke(j2cl);

    Method getIncrementalManagerMethod =
            J2clTranspiler.class.getDeclaredMethod("getIncrementalManager");
    getIncrementalManagerMethod.setAccessible(true);
    IncrementalManager incrementalManager = (IncrementalManager) getIncrementalManagerMethod.invoke(j2cl);

    return new TranspileResult(problems, outputPath, incrementalManager);
  }

  public TranspilerTester compile() {
    try {
      Path tempDir = (this.tempPath != null) ? this.tempPath : Files.createTempDirectory(temporaryDirectoryPrefix);

      if (tempDir != null) {
        // 1. Create an input directory
        Path inputPath = tempDir.resolve("input");
        Files.createDirectories(inputPath);

        checkState(!packageName.contains(".") && !packageName.contains("/"));
        Path packagePath = inputPath.resolve(packageName);
        Files.createDirectories(packagePath);

        // 2. Create all new declared files on disk
        files.forEach(file -> file.createFileIn(inputPath));

        List<String> fileNames = getAllFiles(packagePath).stream()
                                      .filter(Predicates.or(File::isJavaSourceFile, File::isSrcJar))
                                      .map(file -> inputPath.resolve(file.getFilePath()))
                                      .map(Path::toAbsolutePath)
                                      .map(Path::toString)
                                      .collect(toImmutableList());
        new JavacHelper().compile(fileNames, classPath, classesOutPath.toFile());

      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    return this;
  }

  private ImmutableList<File> getAllFiles(Path packagePath) throws IOException {
    return Files.list(packagePath).map(path -> new File(path, null)).collect(toImmutableList());
  }

  private Path createNativeZipFile(Path inputPath, List<File> nativeSources, String outputFileName)
      throws IOException {
    Path zipFilePath = inputPath.resolve(outputFileName);

    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFilePath.toFile()))) {
      for (File nativeSource : nativeSources) {
        Path nativeSourceAbsolutePath =
            inputPath.resolve(nativeSource.getFilePath()).toAbsolutePath();
        out.putNextEntry(new ZipEntry(inputPath.relativize(nativeSourceAbsolutePath).toString()));
        Files.copy(nativeSourceAbsolutePath, out);
        out.closeEntry();
      }
    }

    return zipFilePath;
  }

  private Path getPackageRelativePath(String compilationUnitName) {
    Path filePath = Paths.get(compilationUnitName);
    return packageName != null && !packageName.isEmpty()
        ? Paths.get(packageName).resolve(filePath)
        : filePath;
  }

  private TranspilerTester() {}
}
