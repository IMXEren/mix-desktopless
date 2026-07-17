/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.cli

import app.morphe.library.logging.Logger
import app.morphe.cli.command.MainCommand
import picocli.CommandLine

fun main(args: Array<String>) {
    Logger.setDefault()
    CommandLine(MainCommand).execute(*args).let(System::exit)
}
