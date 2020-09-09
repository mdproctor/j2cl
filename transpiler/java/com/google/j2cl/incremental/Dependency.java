package com.google.j2cl.incremental;

import java.util.Objects;

public class Dependency {
    private DependencyType type;
    private TypeInfo       callee; //
    private TypeInfo       caller; //

    private String name;
    private String signature;
    private String returnType;

    private boolean calleeHasImpact;

    public Dependency(DependencyType type, TypeInfo callee, TypeInfo caller, String name, String signature, String returnType) {
        this.type = type;
        this.callee = callee;
        this.caller = caller;
        this.name = name;
        this.signature = signature;
        this.returnType = returnType;
    }

    public DependencyType getType() {
        return type;
    }

    public TypeInfo getCallee() {
        return callee;
    }

    public TypeInfo getCaller() {
        return caller;
    }

    public String getName() {
        return name;
    }

    public String getSignature() {
        return signature;
    }

    public String getReturnType() {
        return returnType;
    }

    public void remove() {
        callee.getIncomingDependencies().remove(this);
        caller.getOutgoingDependencies().remove(this);
    }

    public boolean isCalleeHasImpact() {
        return calleeHasImpact;
    }

    public void setCalleeHasImpact(boolean calleeHasImpact) {
        this.calleeHasImpact = calleeHasImpact;
    }

    @Override public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Dependency that = (Dependency) o;
        return calleeHasImpact == that.calleeHasImpact &&
               type == that.type &&
               callee.equals(that.callee) &&
               caller.equals(that.caller) &&
               name.equals(that.name) &&
               signature.equals(that.signature) &&
               returnType.equals(that.returnType);
    }

    @Override public int hashCode() {
        System.out.println(this + ":" + Objects.hash(type, callee, caller, name, signature, returnType, calleeHasImpact));
        return Objects.hash(type, callee, caller, name, signature, returnType, calleeHasImpact);
    }

    @Override public String toString() {
        return "Dependency{" +
               "type=" + type +
               ", callee=" + callee +
               ", caller=" + caller +
               ", name='" + name + '\'' +
               ", signature='" + signature + '\'' +
               ", returnType='" + returnType + '\'' +
               ", calleeHasImpact=" + calleeHasImpact +
               '}';
    }
}
