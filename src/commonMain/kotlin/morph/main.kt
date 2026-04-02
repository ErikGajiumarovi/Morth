package morph

import morph.ast.EntryFile
import morph.ast.MorphException
import morph.ast.MorphParseError
import morph.ast.MorphProgram
import morph.ast.ParsedSourceFile
import morph.ast.SourceLocation
import morph.interpreter.Environment
import morph.interpreter.Interpreter
import morph.lexer.Lexer
import morph.parser.Parser
import morph.platform.RuntimePlatform
import morph.typechecker.CheckedProgram
import morph.typechecker.TypeChecker
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

private val systemFs = FileSystem.SYSTEM
private val cwd: Path by lazy { systemFs.canonicalize(".".toPath()) }
private val morphExtensions = setOf("types", "funs", "impl", "link", "entry")

fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "run" -> runCommand(args.getOrNull(1) ?: failUsage("Missing entry file"))
            "check" -> checkCommand(args.getOrNull(1) ?: failUsage("Missing directory"))
            "repl" -> replCommand()
            else -> failUsage("Usage: morph <run|check|repl> ...")
        }
    } catch (error: MorphException) {
        RuntimePlatform.stderrLine("[MORPH ERROR] ${error.phase.padEnd(9)} | ${error.errorLocation.render()} | ${error.message}")
        RuntimePlatform.exit(1)
    } catch (error: Exception) {
        RuntimePlatform.stderrLine("[MORPH ERROR] Internal  | <internal>:1 | ${error.message}")
        RuntimePlatform.exit(1)
    }
}

private fun runCommand(entryPathText: String) {
    val entryPath = requireRegularFile(entryPathText, "Entry file")
    val dir = entryPath.parent ?: cwd
    val otherFiles = listMorphFiles(dir).filter { it.extensionOrEmpty() != "entry" }
    val program = loadProgram(otherFiles + entryPath)
    val entry = program.files.firstOrNull { it.path == entryPath.toString() }?.file as? EntryFile
        ?: throw MorphParseError(SourceLocation(entryPath.toString(), 1, 1), "Entry file did not parse as an entry")
    val checked = TypeChecker().check(program)
    val interpreter = Interpreter(checked)
    val result = interpreter.evaluateEntry(entry)
    println(interpreter.renderValue(result))
}

private fun checkCommand(directoryText: String) {
    val directory = requireDirectory(directoryText)
    val program = loadProgram(listMorphFiles(directory))
    TypeChecker().check(program)
    println("Check OK")
}

private fun replCommand() {
    val emptyProgram = MorphProgram(emptyList())
    val checked = CheckedProgram(
        program = emptyProgram,
        types = emptyMap(),
        funs = emptyMap(),
        resolvedImpls = emptyMap(),
        links = emptyMap(),
        variants = emptyMap(),
    )
    val interpreter = Interpreter(checked)
    val env = Environment(emptyMap(), emptyMap(), emptyMap())
    println("Morph REPL. Press Enter on an empty line to exit.")
    while (true) {
        print("> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) {
            break
        }
        try {
            val expr = Parser(Lexer(line, "<repl>").lex()).parseExpression()
            val value = interpreter.evalExpr(expr, env)
            println(interpreter.renderValue(value))
        } catch (error: MorphException) {
            RuntimePlatform.stderrLine("[MORPH ERROR] ${error.phase.padEnd(9)} | ${error.errorLocation.render()} | ${error.message}")
        }
    }
}

private fun loadProgram(files: List<Path>): MorphProgram {
    val parsed = files
        .distinctBy { it.toString() }
        .sortedBy { it.name }
        .map { file ->
            val source = systemFs.source(file).buffer()
            val text = try {
                source.readUtf8()
            } finally {
                source.close()
            }
            val tokens = Lexer(text, file.toString()).lex()
            val morphFile = Parser(tokens).parseFile()
            ParsedSourceFile(file.toString(), morphFile)
        }
    return MorphProgram(parsed)
}

private fun listMorphFiles(directory: Path): List<Path> {
    return systemFs.list(directory)
        .filter { path ->
            val metadata = try {
                systemFs.metadata(path)
            } catch (_: Exception) {
                null
            }
            metadata?.isRegularFile == true && path.extensionOrEmpty() in morphExtensions
        }
        .sortedBy { it.name }
}

private fun requireRegularFile(pathText: String, kind: String): Path {
    val resolved = canonicalizePath(pathText)
    val metadata = try {
        systemFs.metadata(resolved)
    } catch (_: Exception) {
        null
    }
    if (metadata?.isRegularFile != true) {
        throw MorphParseError(SourceLocation(pathText, 1, 1), "$kind not found")
    }
    return resolved
}

private fun requireDirectory(pathText: String): Path {
    val resolved = canonicalizePath(pathText)
    val metadata = try {
        systemFs.metadata(resolved)
    } catch (_: Exception) {
        null
    }
    if (metadata?.isDirectory != true) {
        throw MorphParseError(SourceLocation(pathText, 1, 1), "Directory not found")
    }
    return resolved
}

private fun canonicalizePath(pathText: String): Path {
    val rawPath = pathText.toPath()
    return try {
        systemFs.canonicalize(rawPath)
    } catch (_: Exception) {
        if (rawPath.isAbsolute) {
            rawPath
        } else {
            cwd / pathText
        }
    }
}

private fun failUsage(message: String): Nothing {
    RuntimePlatform.stderrLine(message)
    RuntimePlatform.exit(1)
}

private fun Path.extensionOrEmpty(): String = name.substringAfterLast('.', "")
