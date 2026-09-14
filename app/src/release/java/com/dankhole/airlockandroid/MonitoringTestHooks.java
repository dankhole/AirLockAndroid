package com.dankhole.airlockandroid;

/** Release builds never alter foreground-query timing. */
final class MonitoringTestHooks {
    private MonitoringTestHooks() {
    }

    static void afterForegroundQuery(String candidatePackage) {
    }
}
