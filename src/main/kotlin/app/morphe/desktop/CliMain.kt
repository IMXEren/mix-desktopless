/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.desktop

import app.morphe.library.logging.Logger
import app.morphe.desktop.command.MainCommand
import picocli.CommandLine

fun main(args: Array<String>) {
    Logger.setDefault()
    CommandLine(MainCommand).execute(*args).let(System::exit)
}
