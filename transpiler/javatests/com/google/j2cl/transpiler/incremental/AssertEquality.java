package com.google.j2cl.transpiler.incremental;

import com.google.j2cl.incremental.IncrementalManager;
import com.google.j2cl.incremental.TypeInfo;

import static org.junit.Assert.*;

public class AssertEquality {

    public static void assertReferenceManagerEquals(IncrementalManager ref1, IncrementalManager ref2) {
        assertEquals(ref1.getTypeInfoLookup().size(), ref2.getTypeInfoLookup().size());

        for (TypeInfo typeInfo1 : ref1.getTypeInfoLookup().values()) {
            TypeInfo typeInfo2 = ref2.getTypeInfoLookup().get(typeInfo1.getUniqueId());
            assertTypeInfoEquals(typeInfo1, typeInfo2);
        }

        //assertEquals(ref1.getTypesImpactCaller(), ref2.getTypesImpactCaller());
    }

    public static void assertTypeInfoEquals(TypeInfo typeInfo1, TypeInfo typeInfo2) {
        System.out.println("assert:" + typeInfo1.getUniqueId() + ":" + typeInfo2.getUniqueId());

        assertEquals(typeInfo1.getUniqueId(), typeInfo2.getUniqueId());

        assertEquals(typeInfo1.getEnclosingTypeInfo(), typeInfo2.getEnclosingTypeInfo());

        assertEquals(typeInfo1.getInnerTypes(), typeInfo2.getInnerTypes());

        assertEquals(typeInfo1.getOutgoingDependencies(), typeInfo2.getOutgoingDependencies());

        assertEquals(typeInfo1.getIncomingDependencies(), typeInfo2.getIncomingDependencies());
    }
}
