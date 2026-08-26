package ru.souz.android.python

import android.content.Context
import ru.souz.android.sandbox.AndroidPythonCommandRunner

/** Builds without the embedded interpreter: skills requesting PYTHON get the sandbox's own error. */
fun androidPythonCommandRunner(context: Context): AndroidPythonCommandRunner? = null
