package com.batteryscope;

interface IBatteryDiagnosticsService {
    void destroy() = 16777114;
    String scan();
}
