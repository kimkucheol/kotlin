package org.jetbrains.kotlin.native.interop.gen.jvm

import org.jetbrains.kotlin.native.interop.indexer.Language
import org.jetbrains.kotlin.native.interop.indexer.NativeIndex
import org.jetbrains.kotlin.native.interop.indexer.NativeLibrary
import org.jetbrains.kotlin.native.interop.indexer.buildNativeIndex
import java.io.File
import java.lang.IllegalArgumentException
import java.util.*

fun main(args: Array<String>) {
    val konanHome = System.getProperty("konan.home")!!

    // TODO: remove OSX defaults.
    val substitutions = mapOf(
            "arch" to (System.getenv("TARGET_ARCH") ?: "x86-64"),
            "os" to (System.getenv("TARGET_OS") ?: detectHost())
    )

    processLib(konanHome, substitutions, args.asList())
}

private fun detectHost():String {
    val os =System.getProperty("os.name")
    when (os) {
        "Linux" -> return "linux"
        "Windows" -> return "win"
        "Mac OS X" -> return "osx"
        "FreeBSD" -> return "freebsd"
        else -> {
            throw IllegalArgumentException("we don't know ${os} value")
        }
    }
}

// Performs substitution similar to:
//  foo = ${foo} ${foo.${arch}} ${foo.${os}}
private fun substitute(properties: Properties, substitutions: Map<String, String>) {
    for (key in properties.stringPropertyNames()) {
        if (key.contains('.')) {
            continue
        }
        var value = ""
        for (substitution in substitutions.keys) {
            val property = properties.getProperty(key + "." + substitutions[substitution])
            if (property != null) {
                value += " " + property
            }
        }
        if (value != "") {
            properties.setProperty(key, properties.getProperty(key, "") + " " + value)
        }
    }
}

private fun getArgPrefix(arg: String): String? {
    val index = arg.indexOf(':')
    if (index == -1) {
        return null
    } else {
        return arg.substring(0, index)
    }
}

private fun dropPrefix(arg: String): String? {
    val index = arg.indexOf(':')
    if (index == -1) {
        return null
    } else {
        return arg.substring(index + 1)
    }
}

private fun ProcessBuilder.runExpectingSuccess() {
    println(this.command().joinToString(" "))

    val res = this.start().waitFor()
    if (res != 0) {
        throw Error("Process finished with non-zero exit code: $res")
    }
}

private fun Properties.getSpaceSeparated(name: String): List<String> {
    return this.getProperty(name)?.split(' ') ?: emptyList()
}

private fun Properties.getOsSpecific(name: String): String? {
    val host = detectHost()
    return this.getProperty("$name.$host")
}

private fun List<String>?.isTrue(): Boolean {
    // The rightmost wins, null != "true".
    return this?.last() == "true"
}

private fun runCmd(command: Array<String>, workDir: File, verbose: Boolean = false) {
        if (verbose) println(command)
        ProcessBuilder(*command)
                .directory(workDir)
                .inheritIO()
                .runExpectingSuccess()
}

private fun Properties.defaultCompilerOpts(dependencies: String): List<String> {
    val sysRootDir = this.getOsSpecific("sysRoot")!!
    val sysRoot= "$dependencies/$sysRootDir"
    val llvmHomeDir = this.getOsSpecific("llvmHome")!!
    val llvmHome = "$dependencies/$llvmHomeDir"
    val llvmVersion = this.getProperty("llvmVersion")!!

    // StubGenerator passes the arguments to libclang which 
    // works not exactly the same way as the clang binary and 
    // (in particular) uses different default header search path.
    // See e.g. http://lists.llvm.org/pipermail/cfe-dev/2013-November/033680.html
    // We workaround the problem with -isystem flag below.
    val isystem = "$llvmHome/lib/clang/$llvmVersion/include"

    val host = detectHost()
    when (host) {
        "osx" -> 
            return listOf(
                "-isystem", isystem,
                "-B$sysRoot/usr/bin",
                "--sysroot=$sysRoot",
                "-mmacosx-version-min=10.10")
        "linux" -> {
            val gccToolChainDir = this.getOsSpecific("gccToolChain")!!
            val gccToolChain= "$dependencies/$gccToolChainDir"

            return listOf(
                "-isystem", isystem,
                "--gcc-toolchain=$gccToolChain",
                "-L$llvmHome/lib",
                "-B$sysRoot/../bin",
                "--sysroot=$sysRoot")
        }
        else -> error("Unexpected host: ${host}")
    }
}

private fun loadProperties(file: File?, substitutions: Map<String, String>): Properties {
    val result = Properties()
    file?.bufferedReader()?.use { reader ->
        result.load(reader)
    }
    substitute(result, substitutions)
    return result
}

private fun processLib(konanHome: String,
                       substitutions: Map<String, String>,
                       commandArgs: List<String>) {

    val args = commandArgs.groupBy ({ getArgPrefix(it)!! }, { dropPrefix(it)!! }) // TODO

    val userDir = System.getProperty("user.dir")
    val ktGenRoot = args["-generated"]?.single() ?: userDir
    val nativeLibsDir = args["-natives"]?.single() ?: userDir
    val platformName = args["-target"]?.single() ?: "jvm"

    val platform = KotlinPlatform.values().single { it.name.equals(platformName, ignoreCase = true) }

    val defFile = args["-def"]?.single()?.let { File(it) }
    val config = loadProperties(defFile, substitutions)

    val konanFileName = args["-properties"]?.single() ?:
        "${konanHome}/konan/konan.properties"
    val konanFile = File(konanFileName)
    val konanProperties = loadProperties(konanFile, mapOf())

    val llvmHome = konanProperties.getOsSpecific("llvmHome")!!
    val dependencies = File("$konanHome/../dependencies/all").canonicalPath
    val llvmInstallPath = "$dependencies/$llvmHome"
    val additionalHeaders = args["-h"].orEmpty()
    val additionalCompilerOpts = args["-copt"].orEmpty()
    val additionalLinkerOpts = args["-lopt"].orEmpty()
    val generateShims = args["-shims"].isTrue()
    val verbose = args["-verbose"].isTrue()

    val defaultOpts = konanProperties.defaultCompilerOpts(dependencies)
    val headerFiles = config.getSpaceSeparated("headers") + additionalHeaders
    val compilerOpts = 
        config.getSpaceSeparated("compilerOpts") +
        defaultOpts + additionalCompilerOpts 
    val compiler = "clang"
    val language = Language.C
    val linkerOpts = 
        config.getSpaceSeparated("linkerOpts").toTypedArray() +
        defaultOpts + additionalLinkerOpts 
    val linker = args["-linker"]?.singleOrNull() ?: config.getProperty("linker") ?: "clang"
    val excludedFunctions = config.getSpaceSeparated("excludedFunctions").toSet()

    val fqParts = args["-pkg"]?.singleOrNull()?.let {
        it.split('.')
    } ?: defFile!!.name.split('.').reversed().drop(1)

    val outKtFileName = fqParts.last() + ".kt"

    val outKtPkg = fqParts.joinToString(".")
    val outKtFileRelative = (fqParts + outKtFileName).joinToString("/")
    val outKtFile = File(ktGenRoot, outKtFileRelative)

    val libName = fqParts.joinToString("") + "stubs"

    val nativeIndex = buildNativeIndex(NativeLibrary(headerFiles, compilerOpts, language))

    val gen = StubGenerator(nativeIndex, outKtPkg, libName, excludedFunctions, generateShims, platform)

    outKtFile.parentFile.mkdirs()
    outKtFile.bufferedWriter().use { out ->
        gen.withOutput({ out.appendln(it) }) {
            gen.generateKotlinFile()
        }
    }


    val outCFile = createTempFile(suffix = ".c")

    outCFile.bufferedWriter().use { out ->
        gen.withOutput({ out.appendln(it) }) {
            gen.generateCFile(headerFiles)
        }
    }

    File(nativeLibsDir).mkdirs()

    val workDir = defFile?.parentFile ?: File(System.getProperty("java.io.tmpdir"))

    if (platform == KotlinPlatform.JVM) {

        val outOFile = createTempFile(suffix = ".o")

        val javaHome = System.getProperty("java.home")
        val compilerArgsForJniIncludes = listOf("", "linux", "darwin").map { "-I$javaHome/../include/$it" }.toTypedArray()

        val compilerCmd = arrayOf("$llvmInstallPath/bin/$compiler", *compilerOpts.toTypedArray(),
                *compilerArgsForJniIncludes,
                "-c", outCFile.path, "-o", outOFile.path)

        runCmd(compilerCmd, workDir, verbose)

        val outLib = nativeLibsDir + "/" + System.mapLibraryName(libName)

        val linkerCmd = arrayOf("$llvmInstallPath/bin/$linker", *linkerOpts, outOFile.path, "-shared", "-o", outLib,
                "-Wl,-flat_namespace,-undefined,dynamic_lookup")

        runCmd(linkerCmd, workDir, verbose)

        outOFile.delete()
    } else if (platform == KotlinPlatform.NATIVE) {
        val outBcName = libName + ".bc"
        val outLib = nativeLibsDir + "/" + outBcName
        val compilerCmd = arrayOf("$llvmInstallPath/bin/$compiler", *compilerOpts.toTypedArray(),
                "-emit-llvm", "-c", outCFile.path, "-o", outLib)

        runCmd(compilerCmd, workDir, verbose)
    }

    outCFile.delete()
}
