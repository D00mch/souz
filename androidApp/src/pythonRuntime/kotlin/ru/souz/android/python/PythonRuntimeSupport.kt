package ru.souz.android.python

import android.content.Context
import ru.souz.android.sandbox.AndroidPythonCommandRunner

fun androidPythonCommandRunner(context: Context): AndroidPythonCommandRunner =
    ChaquopyPythonSkillRunner(context)
