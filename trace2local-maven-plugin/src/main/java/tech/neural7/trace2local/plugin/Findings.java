package tech.neural7.trace2local.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Achados da engenharia reversa + auditoria de logs (imutável após o scan). */
public final class Findings {

    public record Endpoint(String method, String path, String handler) {}

    public final List<Endpoint> endpoints = new ArrayList<>();
    public final List<String> businessMethods = new ArrayList<>();
    public final Set<String> awsServices = new TreeSet<>();
    public final List<String> systemOutSites = new ArrayList<>();
    public boolean usesJdbc;
    public int slf4jUsages;
    public int printStackTraceSites;
    public int scannedClasses;
    public boolean hasLogbackConfig;
    public boolean hasLog4j2Config;
    public boolean logPatternHasTraceIds;
    public boolean hasBusinessGlossary;
}
