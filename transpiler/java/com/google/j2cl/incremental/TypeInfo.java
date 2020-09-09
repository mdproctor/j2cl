package com.google.j2cl.incremental;

import java.util.ArrayList;
import java.util.List;

import com.google.j2cl.ast.DeclaredTypeDescriptor;

public class TypeInfo {
    private DeclaredTypeDescriptor type;
    private String                 uniqueId;
    private List<Dependency>       outgoingDependencies = new ArrayList<>(); // points of use, where method is called
    private List<Dependency>       incomingDependencies = new ArrayList<>(); // points of declaration, where method is defined

    private TypeInfo enclosingTypeInfo;
    private List<TypeInfo> innerTypes = new ArrayList<>();

    public TypeInfo(DeclaredTypeDescriptor type) {
        setType(type);
        this.uniqueId = type.getUniqueId();
    }

    public TypeInfo(String uniqueId) {
        setType(null);
        this.uniqueId = uniqueId;
    }

    public String getUniqueId() {
        return uniqueId;
    }

    public TypeInfo getEnclosingTypeInfo() {
        return enclosingTypeInfo;
    }

    public void setEnclosingTypeInfo(TypeInfo enclosingTypeInfo) {
        this.enclosingTypeInfo = enclosingTypeInfo;
    }

    public List<TypeInfo> getInnerTypes() {
        return innerTypes;
    }

    public DeclaredTypeDescriptor getType() {
        return type;
    }

    public void setType(DeclaredTypeDescriptor type) {
        this.type = type;
    }

    public List<Dependency> getOutgoingDependencies() {
        return outgoingDependencies;
    }

    public List<Dependency> getIncomingDependencies() {
        return incomingDependencies;
    }

    public boolean addOutgoingDependency(Dependency dependency) {
        return outgoingDependencies.add(dependency);
    }

    public boolean addIncomingDependency(Dependency dependency) {
        return incomingDependencies.add(dependency);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TypeInfo that = (TypeInfo) o;
        return uniqueId.equals(that.uniqueId);
    }

    @Override
    public int hashCode() {
        return uniqueId.hashCode();
    }

    @Override public String toString() {
        return "TypeInfo{" +
               " uniqueId='" + uniqueId + '\'' +
               '}';
    }
}
