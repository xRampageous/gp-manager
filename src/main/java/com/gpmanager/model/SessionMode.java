package com.gpmanager;

/**
 * AUTO sessions follow the detected activity; every session the engine opens (Overall and each
 * Grind) is AUTO. GENERAL is the plain default of a bare {@code new Session}.
 */
enum SessionMode {
    AUTO,
    GENERAL
}
