package com.google.j2cl.sdm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.j2cl.ast.AbstractVisitor;
import com.google.j2cl.ast.DeclaredTypeDescriptor;
import com.google.j2cl.ast.FieldAccess;
import com.google.j2cl.ast.FieldDescriptor;
import com.google.j2cl.ast.MethodCall;
import com.google.j2cl.ast.MethodDescriptor;
import com.google.j2cl.ast.NewInstance;
import com.google.j2cl.ast.Type;
import com.google.j2cl.ast.TypeDeclaration;
import com.google.j2cl.ast.TypeDescriptor;

public class ReferenceManager {
    private Map<String, TypeInfo> typeInfoLookup = new HashMap<>();

    private Set<TypeInfo> typesImpactCaller = new HashSet<>();

    public ReferenceManager() {
    }

    public TypeInfo get(String uniqueId) {
        return typeInfoLookup.get(uniqueId);
    }

    public TypeInfo get(DeclaredTypeDescriptor type) {
        String uniqueId = type.getUniqueId();
        TypeInfo typeInfo = get(uniqueId);
        if (typeInfo == null) {
            typeInfo = new TypeInfo(type);
            typeInfoLookup.put(type.getUniqueId(), typeInfo);
        }
        if (typeInfo.getType() == null) {
            typeInfo.setType(type);
        }
        return typeInfo;
    }

    public Map<String, TypeInfo> getTypeInfoLookup() {
        return typeInfoLookup;
    }

    public Set<TypeInfo> getTypesImpactCaller() {
        return typesImpactCaller;
    }

    public void processChangeSet(ChangeSet changeSet) {
        for (String type : changeSet.getRemoved()) {
            TypeInfo typeInfo = typeInfoLookup.get("?" + type);
            if ( typeInfo.getEnclosingTypeInfo() != null ) {
                // Cannot directly remove inner classes.
                // They should be excluded, before being added to the changeset.
                throw new IllegalStateException("Cannot directly remove inner classes");
            }
            remove(typeInfo);
        }

        // This can be cleared as soon as applied, it won't need redoing on failure.
        changeSet.getRemoved().clear();

        for (String type : changeSet.getUpdated()) {
            TypeInfo typeInfo = get("?" + type);
            prepareForUpdateType(typeInfo);
        }
        // update remains, as we do not clear this until we get a successful transpilation
        // until then updated and added keeps growing.

        // Expand the list of types that need to be transpiled
        buildImpacted(changeSet);
        for (String type : changeSet.getImpacted()) {
            TypeInfo typeInfo = get(type);
            prepareForUpdateType(typeInfo);
        }
    }

    private void buildImpacted(ChangeSet changeSet) {
        changeSet.getImpacted().clear(); // always clear this before rebuilding
        for (String type : changeSet.getAdded()) {
            buildImpacted(changeSet, type);
        }

        for (String type : changeSet.getUpdated()) {
            buildImpacted(changeSet, type);
        }
    }

    private void buildImpacted(ChangeSet changeSet, String type) {
        TypeInfo typeInfo = typeInfoLookup.get("?" + type);
        for (Dependency dep : typeInfo.getIncomingDependencies()) {
            if (dep.isCalleeHasImpact()) {
                TypeInfo caller = dep.getCaller();
                if ( !changeSet.getUpdated().contains(caller)) {
                    // don't add it to the impacted list, if it's already in the updated
                    changeSet.getImpacted().add(caller.getUniqueId());
                }
            }
        }
    }

    public void prepareForUpdateType(TypeInfo typeInfo) {
        for (TypeInfo inner : typeInfo.getInnerTypes()) {
            prepareForUpdateType(inner);
        }

        // If any jsinterop annotations renames things, it will be re-added via the visitor
        typesImpactCaller.remove(typeInfo);

        // Remove all method call references (where this class calls other methods).
        // The visitor will re-add all method call references.
        List<Dependency> depsSet = typeInfo.getOutgoingDependencies();
        Dependency[] deps = depsSet.toArray(new Dependency[depsSet.size()]);
        for(Dependency dep : deps) {
            dep.remove();
        }

        // Note This type may have references to removed references in thee callee list.
        // These will be updated, when the callers are visited, which must happen for
        // the workspace to compile without errors.

        // Visitor will update with the new DeclaredType
        typeInfo.setType(null);
    }

    public boolean exists(DeclaredTypeDescriptor type) {
        return typeInfoLookup.containsKey(type.getUniqueId());
    }

    public TypeInfo remove(TypeInfo typeInfo) {
        typeInfoLookup.remove(typeInfo.getUniqueId());

        // Iterate and remove all inner, depth first
        if ( !typeInfo.getInnerTypes().isEmpty()) {
            for (TypeInfo inner : typeInfo.getInnerTypes()) {
                remove(inner);
            }
        }

        typesImpactCaller.remove(typeInfo);

        // Remove all method call references (where this class calls other methods).
        List<Dependency> callerDeps = typeInfo.getOutgoingDependencies();
        Dependency[] deps = callerDeps.toArray(new Dependency[callerDeps.size()]);
        for(Dependency dep : deps) {
            dep.remove();
        }

        // Remove all method caller references (where other classes call methods on this one).
        List<Dependency> calleeDeps = typeInfo.getIncomingDependencies();
        deps = calleeDeps.toArray(new Dependency[calleeDeps.size()]);
        for(Dependency dep : deps) {
            dep.remove();
        }

        typeInfo.setType(null);

        return typeInfo;
    }

    List<TypeInfo> typeStack = new ArrayList<>();

    public AbstractVisitor visitor = new AbstractVisitor(){
        @Override public boolean enterMethodCall(MethodCall node) {
            MethodDescriptor methodDescr = node.getTarget();

            TypeInfo callerInfo = typeStack.get(typeStack.size()-1);
            DeclaredTypeDescriptor   type = methodDescr.getEnclosingTypeDescriptor();
            TypeInfo calleeInfo = get(type);

            if (calleeInfo == callerInfo) {
                // cannot depend on itself
                return true;
            }

            String         name       = methodDescr.getName();
            String         signature  = methodDescr.getMethodSignature();
            TypeDescriptor returnType = methodDescr.getReturnTypeDescriptor();

            if (signature.startsWith("$ctor__") ||
                signature.equals("$clinit()") ||
                signature.equals("<init>()")) {
                return true;
            }

            DependencyType depType = signature.equals("$create()") ? DependencyType.NEW_INSTANCE : DependencyType.METHOD_ACCESS;

            Dependency dep = new Dependency(depType, calleeInfo, callerInfo, name, signature, returnType.getUniqueId());
            boolean b1  = calleeInfo.addIncomingDependency(dep);
            boolean b2 = callerInfo.addOutgoingDependency(dep);
            System.out.println(depType + " " + node.getQualifier() + " " + returnType.getUniqueId() + " " + signature + " " + b1 + " " + b2);

            if  ( depType == DependencyType.METHOD_ACCESS) {
                if ( methodDescr.getJsInfo().getJsName() != null &&  !methodDescr.getJsInfo().getJsName().equals(methodDescr.getName())) {
                    // The method is renamed, so this needs to be tracked.
                    typesImpactCaller.add(calleeInfo);
                    dep.setCalleeHasImpact(true);
                }
            } else {
                TypeDeclaration targetType = calleeInfo.getType().getTypeDeclaration();
                if (!targetType.getSimpleJsName().equals(targetType.getSimpleSourceName())) {
                    // The class is renamed, so this needs to be tracked.
                    typesImpactCaller.add(calleeInfo);
                    dep.setCalleeHasImpact(true);
                }
            }

            return true;
        }

        @Override public boolean enterFieldAccess(FieldAccess node) {
            FieldDescriptor fieldDescr = node.getTarget();

            TypeInfo callerInfo = typeStack.get(typeStack.size()-1);
            DeclaredTypeDescriptor calleeType =  fieldDescr.getEnclosingTypeDescriptor();
            TypeInfo calleeInfo = get(calleeType);

            if (calleeInfo == callerInfo) {
                // cannot depend on itself
                return true;
            }

            String         name       = fieldDescr.getName();
            TypeDescriptor returnType =  fieldDescr.getTypeDescriptor();
            Dependency dep = new Dependency(DependencyType.FIELD_ACCESS, calleeInfo, callerInfo, name, null, returnType.getUniqueId());
            boolean b1  = calleeInfo.addIncomingDependency(dep);
            boolean b2 = callerInfo.addOutgoingDependency(dep);
            System.out.println("field access: " + name + " " + node.getTarget().getName() + " " + returnType.getUniqueId() + " " + b1 + " " + b2);

            if ( fieldDescr.getJsInfo().getJsName() != null &&  !fieldDescr.getJsInfo().getJsName().equals(fieldDescr.getName())) {
                // The method is renamed, so this needs to be tracked.
                typesImpactCaller.add(calleeInfo);
                dep.setCalleeHasImpact(true);
            }

            return true;
        }

        @Override
        public boolean enterType(Type node) {
            DeclaredTypeDescriptor   type     =  node.getTypeDescriptor();
            TypeInfo typeInfo =  get(type);
            typeStack.add(typeInfo);
            if (typeInfo.getType()==null) {
                // If the TypeInfo existed previously and was updated, it's type field is nulled, so reconnect to current TypeDescriptor
                typeInfo.setType(type);
            }
            System.out.println("enter type: " + node.getTypeDescriptor().getQualifiedSourceName());

            DeclaredTypeDescriptor parent = type.getEnclosingTypeDescriptor();
            if ( parent != null ) {
                // inner types need to be recorded
                TypeInfo parentTypeInfo = typeInfoLookup.get(parent.getUniqueId());
                parentTypeInfo.getInnerTypes().add(typeInfo);
                typeInfo.setEnclosingTypeInfo(parentTypeInfo);
            }

            // No need to check JsType info here (in the callee defintion), as it will be checked in the caller places.

            return true;
        }

        @Override
        public void exitType(Type node) {
            typeStack.remove(typeStack.size()-1);
            System.out.println("exit type: " + ((DeclaredTypeDescriptor) node.getTypeDescriptor()).getQualifiedSourceName());
        }

        @Override
        public boolean enterNewInstance(NewInstance node)
        {
            // JsType's have their "new" used directly, non JsType have "create$" usde directly, and the "new" is internal.
            MethodDescriptor methodDescr = node.getTarget();

            TypeInfo callerInfo = typeStack.get(typeStack.size()-1);
            DeclaredTypeDescriptor   type = methodDescr.getEnclosingTypeDescriptor();
            TypeInfo calleeInfo = get(type);

            if (calleeInfo == callerInfo) {
                // cannot depend on itself
                return true;
            }

            String         name       = methodDescr.getName();
            String         signature  = methodDescr.getMethodSignature();
            TypeDescriptor returnType = methodDescr.getReturnTypeDescriptor();

            DependencyType depType = DependencyType.NEW_INSTANCE;

            Dependency dep = new Dependency(depType, calleeInfo, callerInfo, name, signature, returnType.getUniqueId());
            boolean b1  = calleeInfo.addIncomingDependency(dep);
            boolean b2 = callerInfo.addOutgoingDependency(dep);
            //System.out.println(depType + " " + node.getQualifier() + " " + returnType.getUniqueId() + " " + signature + " " + b1 + " " + b2);

            TypeDeclaration targetType = calleeInfo.getType().getTypeDeclaration();
            if (!targetType.getSimpleJsName().equals(targetType.getSimpleSourceName())) {
                // The class is renamed, so this needs to be tracked.
                typesImpactCaller.add(calleeInfo);
                dep.setCalleeHasImpact(true);
            }

            return true;
        }
    };
}
