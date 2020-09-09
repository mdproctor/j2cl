package com.google.j2cl.transpiler;

import java.util.List;

import com.google.j2cl.ast.CompilationUnit;

public interface TranspilerObserver {
    public void observe(List<CompilationUnit> j2clUnits);
}
