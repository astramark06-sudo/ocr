package com.batteryscope;

interface IBatteryDiagnosticsService {
    String scan() = 1;
    String setCoolDown(int level) = 2;
    String snapshotLink() = 3;
    void destroy() = 16777114;
}
