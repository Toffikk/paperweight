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

package io.papermc.paperweight.patcher.tasks

import io.papermc.paperweight.tasks.ControllableOutputTask
import io.papermc.paperweight.util.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.io.path.*
import kotlin.io.path.createDirectories
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option

abstract class SimpleRebuildGitPatches : ControllableOutputTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Internal
    @get:Option(option = "filter-patches", description = "Controls if patches should be cleaned up, defaults to true")
    abstract val filterPatches: Property<Boolean>

    override fun init() {
        printOutput.convention(true)
        filterPatches.convention(true)

        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val what = inputDir.path.name
        val patchFolder = patchDir.path
        if (!patchFolder.exists()) {
            patchFolder.createDirectories()
        }

        if (printOutput.get()) {
            println("Formatting patches for $what...")
        }

        if (inputDir.path.resolve(".git/rebase-apply").exists()) {
            // in middle of a rebase, be smarter
            if (printOutput.get()) {
                println("REBASE DETECTED - PARTIAL SAVE")
                val last = inputDir.path.resolve(".git/rebase-apply/last").readText().trim().toInt()
                val next = inputDir.path.resolve(".git/rebase-apply/next").readText().trim().toInt()
                val orderedFiles = patchFolder.useDirectoryEntries("*.patch") { it.toMutableList() }
                orderedFiles.sort()

                for (i in 1..last) {
                    if (i < next) {
                        orderedFiles[i].deleteForcefully()
                    }
                }
            }
        } else {
            patchFolder.deleteRecursively()
            patchFolder.createDirectories()
        }

        Git(inputDir.path)(
            "format-patch",
            "--zero-commit", "--full-index", "--no-signature", "--no-stat", "-N",
            "-o", patchFolder.absolutePathString(),
            "base"
        ).executeSilently()
        val patchDirGit = Git(patchFolder)
        patchDirGit("add", "-A", ".").executeSilently()

        if (filterPatches.get()) {
            cleanupPatches(patchDirGit)
        }

        if (printOutput.get()) {
            println("  Patches saved for $what to ${patchFolder.name}/")
        }
    }

    private fun cleanupPatches(git: Git) {
        val patchFiles = patchDir.path.useDirectoryEntries("*.patch") { it.toMutableList() }
        if (patchFiles.isEmpty()) {
            return
        }
        patchFiles.sort()

        val noChangesPatches = ConcurrentLinkedQueue<Path>()
        val futures = mutableListOf<Future<*>>()

        // Calling out to git over and over again for each `git diff --staged` command is really slow from the JVM
        // so to mitigate this we do it parallel
        val executor = Executors.newWorkStealingPool()
        try {
            for (patch in patchFiles) {
                futures += executor.submit {
                    val hasNoChanges = git("diff", "--staged", patch.name).getText().lineSequence()
                        .filter { it.startsWith('+') || it.startsWith('-') }
                        .filterNot { it.startsWith("+++") || it.startsWith("---") }
                        .all { it.startsWith("+index") || it.startsWith("-index") }

                    if (hasNoChanges) {
                        noChangesPatches.add(patch)
                    }
                }
            }

            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        if (noChangesPatches.isNotEmpty()) {
            git("reset", "HEAD", *noChangesPatches.map { it.name }.toTypedArray()).executeSilently()
            git("checkout", "--", *noChangesPatches.map { it.name }.toTypedArray()).executeSilently()
        }

        if (printOutput.get()) {
            for (patch in patchFiles) {
                println(patch.name)
            }
        }
    }
}
