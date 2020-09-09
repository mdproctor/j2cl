package com.google.j2cl.incremental;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.j2cl.ast.AbstractVisitor;
import com.google.j2cl.ast.CompilationUnit;
import com.google.j2cl.ast.DeclaredTypeDescriptor;
import com.google.j2cl.ast.FieldAccess;
import com.google.j2cl.ast.FieldDescriptor;
import com.google.j2cl.ast.MethodCall;
import com.google.j2cl.ast.MethodDescriptor;
import com.google.j2cl.ast.NewInstance;
import com.google.j2cl.ast.Type;
import com.google.j2cl.ast.TypeDeclaration;
import com.google.j2cl.ast.TypeDescriptor;
import com.google.j2cl.common.FrontendUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.transpiler.J2clTranspilerOptions;

public class IncrementalManager {
    private ChangeSet              changeSet         = new ChangeSet();

    private Map<String, TypeInfo>  typeInfoLookup    = new HashMap<>();

    private J2clTranspilerOptions  options;

    private boolean                firstRun;

    public IncrementalManager(J2clTranspilerOptions options) {
        this.options = options;
        Path path = options.getOutput();
        Path data = Paths.get(path.toAbsolutePath().toString(), "incremental.dat");
        if ( Files.exists(data)) {
            firstRun = false;
            try {
                read(data);
                processChangeSet();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read incremental.dat file", e);
            }
        } else {
            firstRun = true;
        }
    }

    public ChangeSet getChangeSet() {
        return changeSet;
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

    public void processJ2CLUnits(List<CompilationUnit> j2clUnits) throws IOException {
        for (CompilationUnit unit : j2clUnits) {
            // The uniqueId to path was not known when the initial ChangeSet was built, so add additional paths now.
            for (Type type : unit.getTypes()) {
                if ( type.getDeclaration().getEnclosingTypeDeclaration() == null ) {
                    // prefix the ?, so it has matching uniqueId to the DeclaredTypeDescriptor
                    changeSet.getPathToUniqueId().put(unit.getFilePath(), "?" + type.getDeclaration().getUniqueId());
                }
            }
        }
        for (CompilationUnit unit : j2clUnits) {
            unit.accept(visitor);
        }
        write();
    }

    public ImmutableList<FrontendUtils.FileInfo> getSources() {
        if (firstRun) {
            return options.getSources();
        } else {
            return ImmutableList.copyOf(changeSet.getSources());
        }
    }

    public void processChangeSet() {
        System.out.println("removed");
        for (String str : changeSet.getRemoved()) {
            System.out.println(" " + str);
        }
        System.out.println("updated");
        for (String str : changeSet.getUpdated()) {
            System.out.println(" " + str);
        }
        System.out.println("sources");
        for ( FrontendUtils.FileInfo file : changeSet.getSources()) {
            System.out.println(" " + file.sourcePath());
        }

        for (String type : changeSet.getRemoved()) {
            TypeInfo typeInfo = typeInfoLookup.get(type);
            if ( typeInfo.getEnclosingTypeInfo() != null ) {
                // Cannot directly remove inner classes.
                // They should be excluded, before being added to the changeset.
                throw new IllegalStateException("Cannot directly remove inner classes");
            }
            remove(typeInfo);
        }

        for (String type : changeSet.getUpdated()) {
            TypeInfo typeInfo = get(type);
            prepareForUpdateType(typeInfo);
        }

        // Expand the list of types that need to be transpiled
        Set<TypeInfo> impacted = buildImpacted();

        // add those impacted types to the sources list, and also prepare their TypoInfo's for update
        for (TypeInfo typeInfo : impacted) {
            changeSet.getSources().add(changeSet.getUniqueIdToFileInfo().get(typeInfo.getUniqueId()));
            prepareForUpdateType(typeInfo);
        }
    }

    private Set<TypeInfo> buildImpacted() {
        Set<TypeInfo> impacted = new HashSet<>();
        for (String type : changeSet.getUpdated()) {
            buildImpacted(type, impacted);
        }

        return impacted;
    }

    private void buildImpacted(String type, Set<TypeInfo> impacted) {
        TypeInfo typeInfo = typeInfoLookup.get(type);
        for (Dependency dep : typeInfo.getIncomingDependencies()) {
            if (dep.isCalleeHasImpact()) {
                TypeInfo caller = dep.getCaller();

                // if inner, get root parent, as this relates to the path of the source file
                while (caller.getEnclosingTypeInfo() != null) {
                    caller = caller.getEnclosingTypeInfo();
                }

                if ( !changeSet.getUpdated().contains(caller)) {
                    // don't add it to the impacted list, if it's already in the updated
                    impacted.add(caller);
                }
            }
        }
    }

    public void prepareForUpdateType(TypeInfo typeInfo) {
        for (TypeInfo inner : typeInfo.getInnerTypes()) {
            prepareForUpdateType(inner);
        }

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

    public void write() throws IOException {
        Path outputPath = options.getOutput();
        Path dataPath = Paths.get(outputPath.toAbsolutePath().toString(), "incremental.dat");

        try(Writer writer = Files.newBufferedWriter(dataPath, Charset.forName("UTF-8"))){
            write(writer);
        }
    }

    public void write(Writer out) throws IOException {
        ImmutableList<FrontendUtils.FileInfo> files = this.options.getSources();
        out.append(files.size() + System.lineSeparator());

        for (FrontendUtils.FileInfo file : files) {
            FileTime newTime = Files.getLastModifiedTime(Paths.get(file.sourcePath()));
            String uniqueId = changeSet.getPathToUniqueId().get(file.sourcePath());

            if (uniqueId == null) {
                throw new IllegalStateException("No matching TypeInfo for given path " + file.sourcePath());
            }
            out.append(newTime.toMillis() + "," + uniqueId + "," + file.sourcePath() + System.lineSeparator());
        }

        out.append(typeInfoLookup.size() + System.lineSeparator());
        for (Map.Entry<String, TypeInfo> entry : typeInfoLookup.entrySet()) {
            StringBuilder typeString = new StringBuilder();

            TypeInfo typeInfo = entry.getValue();

            typeString.append(typeInfo.getUniqueId());
            typeString.append(',');
            if (typeInfo.getEnclosingTypeInfo() != null) {
                typeString.append(typeInfo.getEnclosingTypeInfo().getUniqueId());
            }

            typeString.append(",[");
            boolean afterFirst = false;
            for(TypeInfo innerType : typeInfo.getInnerTypes()) {
                if (afterFirst) {
                    typeString.append(',');
                }
                typeString.append(innerType.getUniqueId());
                afterFirst = true;
            }
            typeString.append("],");

            StringBuilder outgoing = new StringBuilder();
            StringBuilder incoming = new StringBuilder();
            writeDependency(outgoing, typeInfo.getOutgoingDependencies());
            writeDependency(incoming, typeInfo.getIncomingDependencies());
            typeString.append(outgoing);
            typeString.append(',');
            typeString.append(incoming);

            out.append(typeString);
            out.append(System.lineSeparator());
        }
    }

    private void writeDependency(StringBuilder out, List<Dependency> list) {
        boolean afterFirst = false;
        out.append('[');
        if (list.size() > 0) {
            out.append(list.size() + ",");
        }

        for (Dependency dep : list) {
            if (afterFirst) {
                out.append(",");
            }
            out.append(dep.getType().ordinal());
            out.append(",");
            out.append(dep.getCallee().getUniqueId());
            out.append(",");
            out.append(dep.getCaller().getUniqueId());
            out.append(",");
            out.append("[" + dep.getSignature() + "]"); // [ ... ] scoping is needed as signature has , in them
            out.append(",");
            out.append(dep.getName());
            out.append(",");
            out.append(dep.getReturnType());
            out.append(",");
            out.append(dep.isCalleeHasImpact());
            afterFirst = true;
        }
        out.append(']');
    }

    public void read(Path in) throws IOException {
        List<DelayedSetter> delayed = new ArrayList<>();

        // Due to circular TypeInfo dependencies, this only creates shallow TypeInfos, to ensure all instances exist first
        // It they creates delayed setters, that are applied after to recreate all state.
        try (Scanner lineScanner = new Scanner(in)) {
            if (!lineScanner.hasNext()) {
                throw new IllegalStateException("The contents of the file are empty");
            }

            buildChangeSet(lineScanner);

            readTypeInfos(delayed, lineScanner);
        }

        // recreate the final state, now all TypeInfos exist.
        applyDelayedSetters(delayed);
    }

    private void buildChangeSet(Scanner lineScanner) throws IOException {
        // get old files
        String line = lineScanner.nextLine();
        int fileSize = Integer.valueOf(line);
        Map<String, FrontendUtils.FileInfo> newFiles = filesListToMap();

        for (int i = 0; i < fileSize; i++) {
            line = lineScanner.nextLine();
            try (Scanner scanner = new Scanner(line)) {
                scanner.useDelimiter(",");
                long oldTime = scanner.nextLong();
                String uniqueId = scanner.next();
                String oldFile = scanner.next();

                // remove the keys, anything left over we know is added
                FrontendUtils.FileInfo file = newFiles.remove(oldFile);
                if (file != null) {
                    // this is done twice, as not all the uniqueIds were known when the ChangeSet was built.
                    // pathToUniqueId is later augmented with updated types
                    changeSet.getUniqueIdToFileInfo().put(uniqueId, file);
                    changeSet.getPathToUniqueId().put(file.sourcePath(), uniqueId);

                    FileTime newTime = Files.getLastModifiedTime(Paths.get(file.sourcePath()));
                    if (newTime.toMillis() > oldTime) {
                        // File has new filetime, so it's been updated
                        changeSet.getUpdated().add(uniqueId);

                        // add updated file, to sources to process
                        changeSet.getSources().add(file);
                    }
                } else {
                    // file does not exist, so it was removed
                    changeSet.getRemoved().add(uniqueId);
                }
            }
        }

        // Any keys left over are added files, which must also processed
        for (FrontendUtils.FileInfo file : newFiles.values()) {
            changeSet.getSources().add(file);
        }
    }

    private Map<String, FrontendUtils.FileInfo> filesListToMap() {
        return options.getSources().stream()
                      .collect(Collectors.toMap(FrontendUtils.FileInfo::sourcePath, file -> file));
    }

    private void readTypeInfos(List<DelayedSetter> delayed, Scanner lineScanner) {
        // get the expected number of rows
        String line = lineScanner.nextLine();
        int typeInfoSize = Integer.valueOf(line);

        for (int i = 0; i < typeInfoSize; i++) {
            line = lineScanner.nextLine();

            try (Scanner scanner = new Scanner(line)) {
                scanner.useDelimiter(",");

                String   uniqueId = scanner.next();
                TypeInfo typeInfo = new TypeInfo(uniqueId);
                this.typeInfoLookup.put(uniqueId, typeInfo);

                String enclosingUniqueId = scanner.next();

                delayed.add(new SetEnclosingType(this, typeInfo, enclosingUniqueId));

                readInner(scanner, delayed, typeInfo);

                readDependency(scanner, delayed, typeInfo, AddDependency.OUTGOING);
                readDependency(scanner, delayed, typeInfo, AddDependency.INCOMING);
            }
        }
    }

    private void applyDelayedSetters(List<DelayedSetter> delayed) {
        // Apply the delayed setters
        for (int i = 0; i < delayed.size(); i++) {
            delayed.get(i).apply();
        }
    }

    private void readInner(Scanner scanner, List<DelayedSetter> delayed, TypeInfo typeInfo) {
        String innerString = scanner.next();
        if (!innerString.equals("[]")) {
            innerString = innerString.substring(1); // strip leading [
            while(!innerString.endsWith("]")) {
                delayed.add(new AddInnerType(this, typeInfo, innerString));
                innerString = scanner.next();
            }
            innerString = innerString.substring(0, innerString.length()-1); // strip trailing ]
            delayed.add(new AddInnerType(this, typeInfo, innerString));
        }
    }

    private void readDependency(Scanner scanner, List<DelayedSetter> delayed, TypeInfo typeInfo, int direction) {
        String first = scanner.next();
        if (!first.equals("[]")) {
            int size = Integer.valueOf(first.substring(1));

            for ( int i = 0; i < size; i++) {
                int ordinal = scanner.nextInt();
                String callee     = scanner.next();
                String caller     = scanner.next();
                String signature  = scanner.next().substring(1); // strip leading [
                while (!signature.endsWith("]")) {
                    signature  += "," + scanner.next();
                }
                signature = signature.substring(0, signature.length() - 1);  // strip trailing ]
                String name       = scanner.next();
                String returnType = scanner.next();
                String last       = scanner.next();
                if (last.endsWith("]")) {
                    last = last.substring(0, last.length() - 1);  // strip trailing ]
                }
                boolean calleeHasImpact = Boolean.valueOf(last);

                delayed.add(new AddDependency(this, typeInfo, direction, DependencyType.values()[ordinal],
                                                callee, caller, name, signature, returnType, calleeHasImpact));
            }
        }
    }

    public interface DelayedSetter {
        void apply();
    }

    public static class SetEnclosingType implements DelayedSetter {

        private IncrementalManager incManageer;
        private TypeInfo           target;
        private String           enclosingTypeString;

        public SetEnclosingType(IncrementalManager incManageer, TypeInfo target, String enclosingTypeString) {
            this.incManageer = incManageer;
            this.target = target;
            this.enclosingTypeString = enclosingTypeString;
        }

        @Override
        public void apply() {
            TypeInfo enclosingType = incManageer.get(enclosingTypeString);
            this.target.setEnclosingTypeInfo(enclosingType);
        }
    }

    public static class AddInnerType implements DelayedSetter {

        private IncrementalManager incManageer;
        private TypeInfo           target;
        private String innerTypeString;

        public AddInnerType(IncrementalManager incManageer, TypeInfo target, String innerTypeString) {
            this.incManageer = incManageer;
            this.target = target;
            this.innerTypeString = innerTypeString;
        }

        @Override
        public void apply() {
            TypeInfo innerType = incManageer.get(innerTypeString);
            this.target.getInnerTypes().add(innerType);
        }
    }

    public static class AddDependency implements DelayedSetter {
        public static final int OUTGOING = 0;
        public static final int INCOMING = 1;

        private IncrementalManager incManageer;
        private TypeInfo           target;
        private int                direction;
        private DependencyType     type;
        private String             callee;
        private String             caller;

        private String             name;
        private String             signature;
        private String             returnType;

        private boolean            calleeHasImpact;

        public AddDependency(IncrementalManager incManageer, TypeInfo target, int direction, DependencyType type, String callee, String caller, String name, String signature, String returnType, boolean calleeHasImpact) {
            this.incManageer = incManageer;
            this.target = target;
            this.direction = direction;
            this.type = type;
            this.callee = callee;
            this.caller = caller;
            this.name = name;
            this.signature = signature;
            this.returnType = returnType;
            this.calleeHasImpact = calleeHasImpact;
        }

        @Override
        public void apply() {
            Dependency dep = new Dependency(type, incManageer.get(callee), incManageer.get(caller), name, signature, returnType);
            dep.setCalleeHasImpact(calleeHasImpact);

            switch(direction) {
                case OUTGOING:
                    target.getOutgoingDependencies().add(dep);
                    break;
                case INCOMING:
                    target.getIncomingDependencies().add(dep);
                    break;
            }
        }
    }

    public AbstractVisitor visitor = new AbstractVisitor() {
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
            //System.out.println(depType + " " + node.getQualifier() + " " + returnType.getUniqueId() + " " + signature + " " + b1 + " " + b2);

            if  ( depType == DependencyType.METHOD_ACCESS) {
                if ( methodDescr.getJsInfo().getJsName() != null &&  !methodDescr.getJsInfo().getJsName().equals(methodDescr.getName())) {
                    // The method is renamed, so this needs to be tracked.
                    dep.setCalleeHasImpact(true);
                }
            } else {
                TypeDeclaration targetType = calleeInfo.getType().getTypeDeclaration();
                if (!targetType.getSimpleJsName().equals(targetType.getSimpleSourceName())) {
                    // The class is renamed, so this needs to be tracked.
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
            //System.out.println("field access: " + name + " " + node.getTarget().getName() + " " + returnType.getUniqueId() + " " + b1 + " " + b2);

            if ( fieldDescr.getJsInfo().getJsName() != null &&  !fieldDescr.getJsInfo().getJsName().equals(fieldDescr.getName())) {
                // The method is renamed, so this needs to be tracked.
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
            //System.out.println("enter type: " + node.getTypeDescriptor().getQualifiedSourceName());

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
            //System.out.println("exit type: " + ((DeclaredTypeDescriptor) node.getTypeDescriptor()).getQualifiedSourceName());
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
                dep.setCalleeHasImpact(true);
            }

            return true;
        }
    };


}
