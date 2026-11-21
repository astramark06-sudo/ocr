package com.batteryscope;

interface IBatteryDiagnosticsService {
    String scan() = 1;
    void destroy() = 16777114;
}
