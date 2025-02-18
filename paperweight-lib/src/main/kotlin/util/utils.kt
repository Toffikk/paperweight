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

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.*
import io.papermc.paperweight.DownloadService
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.Constants.paperTaskOutput
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.ThreadLocalRandom
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import org.cadixdev.lorenz.merge.MergeResult
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.MemberMapping
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*

val gson: Gson = GsonBuilder().setPrettyPrinting().registerTypeHierarchyAdapter(Path::class.java, PathJsonConverter()).create()

class PathJsonConverter : JsonDeserializer<Path?>, JsonSerializer<Path?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Path? {
        return if (json != null) Paths.get(json.asString) else null
    }

    override fun serialize(src: Path?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src.toString())
    }
}

inline fun <reified T> Gson.fromJson(file: Any): T =
    file.convertToPath().bufferedReader().use { fromJson(it) }

val ProjectLayout.cache: Path
    get() = projectDirectory.file(".gradle/${Constants.CACHE_PATH}").path
fun ProjectLayout.cacheDir(path: String) = projectDirectory.dir(".gradle/${Constants.CACHE_PATH}").dir(path)
fun ProjectLayout.initSubmodules() {
    Git(projectDirectory.path)("submodule", "update", "--init").executeOut()
}

inline fun <reified T : Task> TaskContainer.providerFor(name: String): TaskProvider<T> {
    return if (names.contains(name)) {
        named<T>(name)
    } else {
        register<T>(name)
    }
}
inline fun <reified T : Task> TaskContainer.configureTask(name: String, noinline configure: T.() -> Unit): TaskProvider<T> {
    return if (names.contains(name)) {
        named(name, configure)
    } else {
        register(name, configure)
    }
}

@Suppress("UNCHECKED_CAST")
val Project.download: Provider<DownloadService>
    get() = gradle.sharedServices.registrations.getByName("download").service as Provider<DownloadService>

fun commentRegex(): Regex {
    return Regex("\\s*#.*")
}

fun redirect(input: InputStream, out: OutputStream): Thread {
    return Thread {
        try {
            input.copyTo(out)
        } catch (e: Exception) {
            throw PaperweightException("", e)
        }
    }.apply {
        isDaemon = true
        start()
    }
}

object UselessOutputStream : OutputStream() {
    override fun write(b: Int) {
    }
}

fun Any.convertToPath(): Path {
    return when (this) {
        is Path -> this
        is File -> this.toPath()
        is FileSystemLocation -> this.path
        is Provider<*> -> this.get().convertToPath()
        else -> throw PaperweightException("Unknown type representing a file: ${this.javaClass.name}")
    }
}

fun Any?.convertToPathOrNull(): Path? {
    if (this == null) {
        return null
    }
    return this.convertToPath()
}

fun Any.convertToUrl(): URL {
    return when (this) {
        is URL -> this
        is URI -> this.toURL()
        is String -> URI.create(this).toURL()
        is Provider<*> -> this.get().convertToUrl()
        else -> throw PaperweightException("Unknown URL type: ${this.javaClass.name}")
    }
}

fun ensureParentExists(vararg files: Any) {
    for (file in files) {
        val parent = file.convertToPath().parent
        try {
            parent.createDirectories()
        } catch (e: Exception) {
            throw PaperweightException("Failed to create directory $parent", e)
        }
    }
}

fun ensureDeleted(vararg files: Any) {
    for (file in files) {
        val f = file.convertToPath()
        try {
            f.deleteRecursively()
        } catch (e: Exception) {
            throw PaperweightException("Failed to delete file $f", e)
        }
    }
}

fun BaseTask.defaultOutput(name: String, ext: String): RegularFileProperty {
    return objects.fileProperty().convention {
        layout.cache.resolve(paperTaskOutput(name, ext)).toFile()
    }
}
fun BaseTask.defaultOutput(ext: String): RegularFileProperty {
    return defaultOutput(name, ext)
}
fun BaseTask.defaultOutput(): RegularFileProperty {
    return defaultOutput("jar")
}

val <T> Optional<T>.orNull: T?
    get() = orElse(null)

inline fun <reified T : Any> Project.contents(contentFile: RegularFileProperty, crossinline convert: (String) -> T): Provider<T> {
    return providers.fileContents(contentFile)
        .asText
        .forUseAtConfigurationTime()
        .map { convert(it) }
}

fun findOutputDir(baseFile: Path): Path {
    var dir: Path
    do {
        dir = baseFile.resolveSibling("${baseFile.name}-" + ThreadLocalRandom.current().nextInt())
    } while (dir.exists())
    return dir
}

val MemberMapping<*, *>.parentClass: ClassMapping<*, *>
    get() = parent as ClassMapping<*, *>

private val emptyMergeResult = MergeResult(null)
fun <T> emptyMergeResult(): MergeResult<T?> {
    @Suppress("UNCHECKED_CAST")
    return emptyMergeResult as MergeResult<T?>
}

inline fun <reified T : Task> TaskContainer.registering(noinline configuration: T.() -> Unit) = registering(T::class, configuration)
inline fun <reified T : Task> TaskContainer.registering() = registering(T::class)
