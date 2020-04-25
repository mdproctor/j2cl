package com.google.j2cl.sdm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChangeSet {
    List<String> removed  = new ArrayList<>();
    List<String> updated  = new ArrayList<>();
    List<String> added  = new ArrayList<>();
    Set<String>  impacted = new HashSet<>();

    public ChangeSet() {
    }

    public List<String> getRemoved() {
        return removed;
    }

    public List<String> getUpdated() {
        return updated;
    }

    public List<String> getAdded() {
        return added;
    }

    public Set<String> getImpacted() {
        return impacted;
    }

    public void clear() {
        this.removed.clear();
        this.updated.clear();
        this.added.clear();
        this.impacted.clear();
    }
}
