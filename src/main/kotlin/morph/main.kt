package morph

import java.io.File
import kotlin.system.exitProcess
import morph.ast.EntryFile
import morph.ast.MorphException
import morph.ast.MorphProgram
import morph.ast.ParsedSourceFile
import morph.ast.SourceLocation
import morph.interpreter.Environment
import morph.interpreter.Interpreter
import morph.lexer.Lexer
import morph.parser.Parser
import morph.typechecker.CheckedProgram
import morph.typechecker.TypeChecker

fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "run" -> runCommand(args.getOrNull(1) ?: failUsage("Missing entry file"))
            "check" -> checkCommand(args.getOrNull(1) ?: failUsage("Missing directory"))
            "repl" -> replCommand()
            else -> failUsage("Usage: morph <run|check|repl> ...")
        }
    } catch (error: MorphException) {
        System.err.println("[MORPH ERROR] ${error.phase.padEnd(9)} | ${error.errorLocation.render()} | ${error.message}")
        exitProcess(1)
    } catch (error: Exception) {
        System.err.println("[MORPH ERROR] Internal  | <internal>:1 | ${error.message}")
        exitProcess(1)
    }
}

private fun runCommand(entryPathText: String) {
    val entryPath = File(entryPathText).absoluteFile.normalize()
    if (!entryPath.exists()) {
        throw morph.ast.MorphParseError(SourceLocation(entryPath.absolutePath, 1, 1), "Entry file not found")
    }
    val dir = entryPath.parentFile ?: File(".").absoluteFile
    val otherFiles = listMorphFiles(dir).filter { it.extension != "entry" }
    val program = loadProgram(otherFiles + entryPath)
    val checked = TypeChecker().check(program)
    val entry = program.files.firstOrNull { it.path == entryPath.absolutePath }?.file as? EntryFile
        ?: throw morph.ast.MorphParseError(SourceLocation(entryPath.absolutePath, 1, 1), "Entry file did not parse as an entry")
    val interpreter = Interpreter(checked)
    val result = interpreter.evaluateEntry(entry)
    println(interpreter.renderValue(result))
}

private fun checkCommand(directoryText: String) {
    val directory = File(directoryText).absoluteFile.normalize()
    if (!directory.isDirectory) {
        throw morph.ast.MorphParseError(SourceLocation(directory.absolutePath, 1, 1), "Directory not found")
    }
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
            System.err.println("[MORPH ERROR] ${error.phase.padEnd(9)} | ${error.errorLocation.render()} | ${error.message}")
        }
    }
}

private fun loadProgram(files: List<File>): MorphProgram {
    val parsed = files
        .map { it.absoluteFile.normalize() }
        .distinctBy { it.absolutePath }
        .sortedBy { it.name }
        .map { file ->
            val text = file.readText()
            val tokens = Lexer(text, file.absolutePath).lex()
            val morphFile = Parser(tokens).parseFile()
            ParsedSourceFile(file.absolutePath, morphFile)
        }
    return MorphProgram(parsed)
}

private fun listMorphFiles(directory: File): List<File> {
    return directory.listFiles()
        ?.filter { it.isFile && it.extension in setOf("types", "funs", "impl", "link", "entry") }
        ?.sortedBy { it.name }
        .orEmpty()
}

private fun failUsage(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}

private fun File.normalize(): File = canonicalFile
