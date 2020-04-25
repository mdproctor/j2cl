package com.google.j2cl.frontend;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.j2cl.ast.AbstractVisitor;
import com.google.j2cl.ast.CompilationUnit;
import com.google.j2cl.sdm.ChangeSet;
import com.google.j2cl.sdm.ReferenceManager;

public class TranspileContext {
    private Map<String, CompilationUnit> j2clUnits;
    private ChangeSet                    changeSet;
    private ReferenceManager             referenceManager = new ReferenceManager();

    public TranspileContext() {
    }

    public Map<String, CompilationUnit> getJ2clUnits() {
        return j2clUnits;
    }

    public void j2clUnitsAsMap(List<CompilationUnit> j2clUnitList) {
        j2clUnits = new HashMap<>();
        for (CompilationUnit unit : j2clUnitList) {
            String key = unit.getPackageName() + "." + unit.getName();
            j2clUnits.put(key, unit);
        }
    }

    public void setJ2clUnits(Map<String, CompilationUnit> j2clUnits) {
        this.j2clUnits = j2clUnits;
    }

    public AbstractVisitor getVisitor() {
        return referenceManager.visitor;
    }

    public ChangeSet getChangeSet() {
        return changeSet;
    }

    public void setChangeSet(ChangeSet changeSet) {
        this.changeSet = changeSet;
    }

    public ReferenceManager getReferenceManager() {
        return referenceManager;
    }
}
