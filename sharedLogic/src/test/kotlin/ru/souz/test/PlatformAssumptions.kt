package ru.souz.test

import org.junit.jupiter.api.Assumptions.assumeFalse

private val isWindows = System.getProperty("os.name").startsWith("Windows")

/** The local sandbox runs shell scripts only on POSIX hosts; Windows hosts need the Docker sandbox for them. */
fun assumeLocalShellScripts() =
    assumeFalse(isWindows, "Local shell scripts are unsupported on Windows hosts; use the Docker sandbox.")
