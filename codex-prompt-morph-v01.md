# Codex Task: Implement Morph v0.1 Interpreter in Kotlin

## Context

The file `morph-grammar-v1.md` in the project root contains the complete language specification
for **Morph** — a functional, purely type-driven programming language. Read it fully before
writing any code.

## Your task

Implement a **tree-walking interpreter** for Morph v0.1 in Kotlin (JVM target).
Do not implement a compiler, do not target LLVM, do not generate bytecode.
A tree-walking interpreter is the correct choice for v0.1: parse source → build AST → walk AST → produce result.

---

## Project structure to create

```
morph/
├── morph-grammar-v1.md          ← already exists, do not modify
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   └── main/
│       └── kotlin/
│           └── morph/
│               ├── main.kt              ← CLI entry point
│               ├── lexer/
│               │   └── Lexer.kt
│               ├── parser/
│               │   └── Parser.kt
│               ├── ast/
│               │   └── Ast.kt
│               ├── typechecker/
│               │   └── TypeChecker.kt
│               ├── interpreter/
│               │   └── Interpreter.kt
│               └── runtime/
│                   └── MorphValue.kt
└── examples/
    ├── user.types
    ├── user.funs
    ├── user.impl
    ├── app.link
    └── app.entry
```

---

## Implementation requirements

### 1. Lexer (`Lexer.kt`)

Tokenize Morph source into a flat token stream.

Keywords (exact 13): `type fun impl link entry unsafe match val where typeclass Query Result it`

Token types needed:
- Keywords (one per keyword above)
- `UPPER_IDENT` — starts with uppercase `[A-Z][a-zA-Z0-9]*` (types, functions, variants)
- `LOWER_IDENT` — starts with lowercase `[a-z][a-zA-Z0-9_]*` (bindings only)
- `ARROW` → `->`
- `CAST_ARROW` → `|>`
- `PIPE` → `|`
- `COLON` → `:`
- `EQUALS` → `=`
- `COMMA` → `,`
- `DOT` → `.`
- `LPAREN` `RPAREN` `LBRACE` `RBRACE` `LBRACKET` `RBRACKET`
- `AND` → `&&`, `OR` → `||`
- `GT` `LT` `GTE` `LTE` `EQEQ` `NEQ`
- `INT_LIT` `FLOAT_LIT` `STRING_LIT` `BOOL_LIT` `UUID_LIT`
- `EOF`

Skip whitespace and `//` line comments.

---

### 2. AST (`Ast.kt`)

Use sealed classes for every AST node. Every node must be a `data class` or `object`.

```kotlin
// Top level
sealed class MorphFile
data class TypesFile(val decls: List<TypeDecl>) : MorphFile()
data class FunsFile(val sigs: List<FunSig>) : MorphFile()
data class ImplFile(val impls: List<ImplDecl>) : MorphFile()
data class LinkFile(val bindings: List<LinkBinding>) : MorphFile()
data class EntryFile(val body: UnsafeBlock) : MorphFile()

// Type declarations
sealed class TypeDecl
data class PrimitiveType(val name: String, val base: BaseType, val constraint: Constraint?) : TypeDecl()
data class NamedType(val name: String, val fields: List<VoField>, val constraint: Constraint?) : TypeDecl()
data class ProductType(val name: String, val fields: List<String>) : TypeDecl()
data class SumType(val name: String, val variants: List<Variant>) : TypeDecl()
data class QueryType(val name: String, val fields: List<String>) : TypeDecl()
data class ResultType(val name: String, val variants: List<Variant>) : TypeDecl()
data class TypeClassDecl(val name: String, val param: String, val sigs: List<FunSig>) : TypeDecl()
data class TypeClassImpl(val typeClass: String, val forType: String, val impls: List<ImplDecl>) : TypeDecl()

data class Variant(val name: String, val fields: List<String>)
data class VoField(val name: String, val typeName: String)

// Functions
data class FunSig(val name: String, val inputType: String, val outputType: String)
data class ImplDecl(val name: String, val deps: List<String>, val body: Block)
data class LinkBinding(val funName: String, val implName: String, val injected: List<String>)

// Expressions
sealed class Expr
data class FunCallExpr(val name: String, val arg: Expr) : Expr()
data class ConstructorExpr(val typeName: String, val args: List<Expr>) : Expr()
data class FieldAccessExpr(val target: Expr, val field: String) : Expr()
data class ValRefExpr(val name: String) : Expr()
data class LiteralExpr(val value: MorphLiteral) : Expr()
data class MatchExpr(val subject: Expr, val arms: List<MatchArm>) : Expr()
data class CastExpr(val expr: Expr, val targetType: String) : Expr()
data class ItExpr(val path: List<String>, val op: CmpOp, val rhs: Expr) : Expr()  // for where constraints

data class MatchArm(val pattern: Pattern, val body: Expr)
sealed class Pattern
data class VariantPattern(val typeName: String, val bindings: List<String>) : Pattern()
object WildcardPattern : Pattern()

// Blocks
data class Block(val vals: List<ValDecl>, val result: Expr, val isUnsafe: Boolean)
data class ValDecl(val name: String, val expr: Expr)
```

---

### 3. Parser (`Parser.kt`)

Write a **hand-written recursive descent parser**. Do not use ANTLR, do not use any parser combinator library.

The parser takes a `List<Token>` and produces a `MorphFile`.

Key rules:
- A file is identified by its content (presence of `type`/`fun`/`impl`/`link`/`entry` keywords at top level)
- `UPPER_IDENT` is used for type names, function names, and variant names — context determines which
- `LOWER_IDENT` is used only for `val` bindings and `match` arm bindings
- `match` is exhaustive — parser collects all arms, type checker validates exhaustiveness later
- `unsafe { }` blocks are syntactically identical to pure blocks except for the `unsafe` keyword

---

### 4. Type Checker (`TypeChecker.kt`)

The type checker receives all parsed files as a `MorphProgram` and performs these checks in order:

**Pass 1 — Build type registry**
Collect all declared types into a `Map<String, TypeDecl>`. Error on duplicate names.

**Pass 2 — Validate function signatures**
Every `fun` in `.funs` must reference types that exist in the registry.

**Pass 3 — Validate link graph**
- Every `fun` must have exactly one `impl` binding in `link`
- Every injected dependency in `impl(Dep1, Dep2)` must also be bound in `link`
- Build a dependency graph and run topological sort — error on cycles

**Pass 4 — Validate match exhaustiveness**
For every `match` expression over a `ResultType` or `SumType`:
- Collect all variant names of the type
- Verify every variant is covered by an arm, or a wildcard `_` is present
- Error with message: `"Non-exhaustive match on type X: missing variants [Y, Z]"`

**Pass 5 — Validate where constraints (static)**
For `PrimitiveType` with a `where` clause: if the construction site provides a literal value,
evaluate the constraint at compile time and error if it fails.
If the value is not statically known, emit a runtime check instead (see interpreter).

**Pass 6 — Validate Result→Query auto-cast (`|>`)**
For `CastExpr(expr, targetType)`:
- Resolve the type of `expr` (must be a `Result` variant)
- Resolve `targetType` (must be a `Query`)
- Check that all type names in the variant's fields are present in the Query's type list
- Error with hint if mismatch: `"Cannot cast Found(User) to GetPostsQuery: missing type UserId"`

---

### 5. Runtime Values (`MorphValue.kt`)

```kotlin
sealed class MorphValue
data class IntValue(val v: Long) : MorphValue()
data class FloatValue(val v: Double) : MorphValue()
data class StringValue(val v: String) : MorphValue()
data class BoolValue(val v: Boolean) : MorphValue()
data class UuidValue(val v: String) : MorphValue()
data class ProductValue(val typeName: String, val fields: Map<String, MorphValue>) : MorphValue()
data class VariantValue(val typeName: String, val variantName: String, val fields: List<MorphValue>) : MorphValue()
data class ListValue(val items: List<MorphValue>) : MorphValue()
data class SetValue(val items: Set<MorphValue>) : MorphValue()
data class MapValue(val entries: Map<MorphValue, MorphValue>) : MorphValue()
object UnitValue : MorphValue()
```

`ProductValue` is used for both product types and VO. Named VO fields are stored by field name. Positional product types are stored by type name as key.

---

### 6. Interpreter (`Interpreter.kt`)

The interpreter receives a type-checked `MorphProgram` and an `Environment`.

```kotlin
class Environment(
    val types: Map<String, TypeDecl>,
    val funs: Map<String, FunSig>,
    val impls: Map<String, ImplDecl>,      // after link resolution
    val vals: MutableMap<String, MorphValue> = mutableMapOf()
) {
    fun child(): Environment = Environment(types, funs, impls, vals.toMutableMap())
}
```

**Evaluation rules:**

`FunCallExpr(name, arg)` — look up resolved impl for `name`, evaluate `arg`, bind as `it` in impl's block scope, evaluate block.

`ConstructorExpr(typeName, args)` — look up type declaration:
- If `PrimitiveType` with constraint → evaluate constraint against value, throw `MorphRuntimeError` if fails
- If `ProductType` → create `ProductValue` with fields keyed by type name
- If `NamedType` → create `ProductValue` with fields keyed by field name
- If variant name → create `VariantValue`

`MatchExpr(subject, arms)` — evaluate subject to a `VariantValue`, try arms in order:
- `VariantPattern(name, bindings)` — match if `variantName == name`, bind positional fields to binding names in child scope
- `WildcardPattern` — always matches

`CastExpr(expr, targetType)` — evaluate expr to `VariantValue`, extract fields, construct `ProductValue` for target `QueryType`.

`FieldAccessExpr(target, field)` — evaluate target:
- If `field` starts with uppercase → lookup by type name key in `ProductValue.fields`
- If `field` starts with lowercase → lookup by field name key (VO named fields)

**unsafe block execution:**
`unsafe` blocks run identically to pure blocks in the interpreter — for v0.1 there is no actual IO.
Mark `unsafe` blocks in the AST so future versions can enforce the boundary.
Print a `[unsafe]` annotation to stderr when entering an unsafe block during eval.

**Runtime constraint check:**
If a `where` constraint was deferred from type checking (value not statically known),
evaluate it during `ConstructorExpr` and throw `MorphRuntimeError("Constraint violated: ...")`.

---

### 7. CLI Entry Point (`main.kt`)

```
morph run <entry-file>       — run a Morph program (resolves all files in same directory)
morph check <directory>      — type-check all .types/.funs/.impl/.link/.entry files
morph repl                   — start interactive REPL (evaluates single expressions)
```

File resolution: given an `.entry` file, scan the same directory for all `.types`, `.funs`, `.impl`, `.link` files and load them as a single `MorphProgram`.

Error output format:
```
[MORPH ERROR] TypeCheck | file.types:12 | Duplicate type name: UserId
[MORPH ERROR] Runtime   | app.entry:3   | Constraint violated: Age(200) where { it > 0 && it < 150 }
[MORPH ERROR] Link      | app.link:5    | Cycle detected: CreateUser -> FindUser -> CreateUser
```

---

### 8. Example files to generate

Generate working example files in `examples/` that demonstrate all major features:

`examples/user.types`:
```morph
type UserId = UUID
type Email  = String where { it.contains("@") }
type Age    = Int    where { it > 0 && it < 150 }
type User   = { UserId, Email, Age }

type GetUserQuery  : Query  = { UserId }
type GetUserResult : Result = Found(User) | NotFound(UserId)
```

`examples/user.funs`:
```morph
fun FindUser : GetUserQuery -> GetUserResult
```

`examples/user.impl`:
```morph
impl FindUserImpl = unsafe {
    match it.UserId {
        UserId(id) -> Found(User(it.UserId, Email("stub@example.com"), Age(30)))
        _          -> NotFound(it.UserId)
    }
}
```

`examples/app.link`:
```morph
link {
    FindUser -> FindUserImpl
}
```

`examples/app.entry`:
```morph
entry = unsafe {
    FindUser(GetUserQuery(UserId("550e8400-e29b-41d4-a716-446655440000")))
}
```

---

## Build setup

Use Gradle with Kotlin DSL. Target JVM 17. No external dependencies except:
- `kotlin-stdlib`
- `kotlinx-coroutines-core` (only if needed for future async — include but don't use yet)

The project must build and run with:
```bash
./gradlew run --args="run examples/app.entry"
```

---

## What NOT to implement in v0.1

- LLVM / native code generation
- Generics beyond typeclass type parameters
- Higher-kinded types
- Actual IO (DB, HTTP) inside unsafe — stub with println for now
- Module system / namespaces
- Reactive streams
- LSP / tooling

---

## Acceptance criteria

The implementation is complete when:

1. `./gradlew build` passes with zero errors
2. `morph run examples/app.entry` executes and prints the result value
3. `morph check examples/` passes with zero errors on valid files
4. `morph check` correctly reports errors for:
   - Non-exhaustive match
   - Cycle in link graph
   - Missing impl for a fun
   - Constraint violation on known literal
5. All AST nodes are sealed classes, all `MorphValue` types are sealed classes
6. No mutable state outside of `Environment.vals`
