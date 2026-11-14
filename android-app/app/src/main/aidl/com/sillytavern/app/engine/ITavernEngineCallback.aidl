package com.sillytavern.app.engine;

interface ITavernEngineCallback {
    void onStateChanged(int stateCode, int port, String url, String detail);
}
