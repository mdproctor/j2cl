package com.google.j2cl.incremental;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.j2cl.common.FrontendUtils;

public class ChangeSet {
    private List<String>                        removed        = new ArrayList<>(); // uniqueIds
    private List<String>                        updated        = new ArrayList<>(); // uniqueIds

    private List<FrontendUtils.FileInfo>        sources       = new ArrayList<>();
    private Map<String, FrontendUtils.FileInfo> uniqueIdToFileInfo = new HashMap<>();

    private Map<String, String>                 pathToUniqueId = new HashMap<>();


    public ChangeSet() {
    }

    public List<String> getRemoved() {
        return removed;
    }

    public List<String> getUpdated() {
        return updated;
    }

    public List<FrontendUtils.FileInfo> getSources() {
        return sources;
    }

    public Map<String, FrontendUtils.FileInfo> getUniqueIdToFileInfo() {
        return uniqueIdToFileInfo;
    }

    public Map<String, String> getPathToUniqueId() {
        return pathToUniqueId;
    }
}
