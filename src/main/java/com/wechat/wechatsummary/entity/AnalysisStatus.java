package com.wechat.wechatsummary.entity;

public enum AnalysisStatus {
    INITIAL_STATE,  // Pristine baseline state (no files exist)
    RUNNING,        // Active looping thread present
    PAUSED,         // Thread stopped but a .temp snapshot remains on disk
    SUCCESS,        // _summary.txt found on disk
    FAILED          // Explicit database error trace detected with no output
}