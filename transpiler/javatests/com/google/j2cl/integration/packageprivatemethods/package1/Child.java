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
package com.google.j2cl.integration.packageprivatemethods.package1;

public class Child extends Parent {
  // exposes Parent.fun().
  @Override
  public String fun() {
    return "Child";
  }

  // exposes SuperParent.fun();
  @Override
  public int bar(int a) {
    return a + 1;
  }

  // does not expose any methods, overrides SuperParent.foo()
  @Override
  public int foo(int a, int b) {
    return a + b + 2;
  }
}