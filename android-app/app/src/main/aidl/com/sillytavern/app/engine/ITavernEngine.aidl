package com.sillytavern.app.engine;

import com.sillytavern.app.engine.ITavernEngineCallback;

interface ITavernEngine {
    void registerCallback(ITavernEngineCallback cb);
    void unregisterCallback(ITavernEngineCallback cb);
    void startEngine();
    void stopEngine();
    int getCurrentState();
    int getBoundPort();
    String getBoundUrl();
    String getAppToken();
    String getLastError();
    String getRecentLogs();
    String getInstalledAssetVersion();
}
