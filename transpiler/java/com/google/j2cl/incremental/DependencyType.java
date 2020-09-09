package com.google.j2cl.incremental;

public enum DependencyType {
    // METHOD and FIELD may have wrapper text at the useby point.
    METHOD_ACCESS, FIELD_ACCESS, NEW_INSTANCE;
}
