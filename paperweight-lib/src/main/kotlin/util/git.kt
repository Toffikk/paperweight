/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2021 Kyle Wood (DemonWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.util

import io.papermc.paperweight.PaperweightException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.exists

class Git(private var repo: Path) {

    @Suppress("unused")
    constructor(repo: Any) : this(repo.convertToPath())

    init {
        if (!repo.exists()) {
            throw PaperweightException("Git directory does not exist: $repo")
        }
    }

    operator fun invoke(vararg args: String): Command {
        val cmd = arrayOf("git", "-c", "commit.gpgsign=false", "-c", "core.safecrlf=false", *args)
        return try {
            Command(ProcessBuilder(*cmd).directory(repo).start(), cmd.joinToString(separator = " "))
        } catch (e: IOException) {
            throw PaperweightException("Failed to execute command: ${cmd.joinToString(separator = " ")}", e)
        }
    }
}

class Command(private val process: Process, private val command: String) {

    private var outStream: OutputStream = UselessOutputStream
    private var errStream: OutputStream = UselessOutputStream

    fun run(): Int {
        if (System.getProperty("paperweight.debug", "false") == "true") {
            // Override all settings for debug
            setup(System.out, System.err)
        }
        try {
            val input = process.inputStream
            val error = process.errorStream
            val buffer = ByteArray(1000)

            while (process.isAlive) {
                // Read both stdout and stderr on the same thread
                // This is important for how Gradle outputs the logs
                if (input.available() > 0) {
                    val count = input.read(buffer)
                    outStream.write(buffer, 0, count)
                }
                if (error.available() > 0) {
                    val count = error.read(buffer)
                    errStream.write(buffer, 0, count)
                }
                Thread.sleep(1)
            }
            // Catch any other output we may have missed
            outStream.write(input.readBytes())
            errStream.write(error.readBytes())
            return process.waitFor()
        } catch (e: Exception) {
            throw PaperweightException("Failed to call git command: $command", e)
        }
    }

    fun runSilently(silenceOut: Boolean = true, silenceErr: Boolean = false): Int {
        silence(silenceOut, silenceErr)
        return run()
    }

    fun runOut(): Int {
        setup(System.out, System.err)
        return run()
    }

    fun execute() {
        val res = run()
        if (res != 0) {
            throw PaperweightException("Command finished with $res exit code: $command")
        }
    }

    fun executeSilently(silenceOut: Boolean = true, silenceErr: Boolean = false) {
        silence(silenceOut, silenceErr)
        execute()
    }

    private fun silence(silenceOut: Boolean, silenceErr: Boolean) {
        val out = if (silenceOut) null else System.out
        val err = if (silenceErr) null else System.err
        setup(out, err)
    }

    fun executeOut() {
        setup(System.out, System.err)
        execute()
    }

    fun setup(out: OutputStream? = null, err: OutputStream? = null): Command {
        outStream = out ?: UselessOutputStream
        errStream = err ?: UselessOutputStream
        return this
    }

    fun getText(): String {
        val out = ByteArrayOutputStream()
        setup(out, System.err)
        execute()
        return String(out.toByteArray(), Charset.defaultCharset())
    }

    @Suppress("unused")
    fun readText(): String? {
        val out = ByteArrayOutputStream()
        setup(out, System.err)
        return if (run() == 0) String(out.toByteArray(), Charset.defaultCharset()) else null
    }
}
