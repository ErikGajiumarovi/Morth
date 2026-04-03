# Morph Language - Grammar Specification v0.2

> Types are objects.
> Pipelines are typed arrows.
> `Query -> Result` declarations live in `.funs`.
> `Result -> Query` declarations live in `.morths`.
> There are no `fun` or `morth` keywords in source code.

This version defines the core language model after removing declaration keywords for pipeline stages.
Stage role is no longer written in the declaration itself.
It is determined by:

- the file extension the declaration appears in
- the direction of the declared signature

The compiler enforces both.

This spec uses `.morths` as the canonical extension for `Result -> Query` declarations.

---

## Design Summary

Morph has two stage kinds in the pipeline:

| Stage kind | Direction         | Declared in  | Body location | `unsafe` allowed |
|------------|-------------------|--------------|---------------|------------------|
| function   | `Query -> Result` | `.funs`      | `.impl`       | Yes, in `.impl` only |
| morth      | `Result -> Query` | `.morths`    | same file     | No |

The core design is:

- `.funs` declares contracts only
- `.impl` provides implementations only for `.funs`
- `.morths` contains complete `Result -> Query` declarations with bodies
- `.link` binds `.funs` to `.impl`
- `.morths` never participate in `link`
- a pipeline composes `.funs` and `.morths` through `|>`

If a transformation needs IO or `unsafe`, it is not a `morth`.
Move that logic into a `.funs` stage.

---

## Keywords

```
type  impl  link  entry  unsafe  match  val  where  Query  Result  it
```

There are no `fun` or `morth` keywords.
Those roles are inferred from file extension plus signature shape.

---

## Program Structure

```ebnf
program      ::= file+
file         ::= types_file | funs_file | morths_file | impl_file | link_file | entry_file

types_file   ::= type_decl+
funs_file    ::= fun_sig+
morths_file  ::= morth_decl+
impl_file    ::= impl_decl+
link_file    ::= "link" "{" link_binding+ "}"
entry_file   ::= "entry" "=" unsafe_block
```

### File-level meaning

- `.types` contains type declarations only
- `.funs` contains `Query -> Result` signatures only
- `.morths` contains `Result -> Query` declarations with bodies
- `.impl` contains implementations only for `.funs`
- `.link` binds function signatures to implementations
- `.entry` contains the root pipeline

The compiler rejects any construct that appears in the wrong file kind.

---

## Declaration Style Without Keywords

Declarations are now written without `fun` or `morth`.

### In `.funs`

```morph
FindUser : GetUserQuery -> GetUserResult
LoadPosts : GetPostsQuery -> GetPostsResult
```

### In `.morths`

```morph
ViewerToFeedQuery : ResolveViewerResult -> LoadFeedQuery = {
    Authenticated(id) -> LoadFeedQuery(ByUser(id))
    Anonymous(id)     -> LoadFeedQuery(BySession(id))
}
```

### Compiler rules

- In `.funs`, only `Query -> Result` signatures are legal
- In `.morths`, only `Result -> Query` declarations are legal
- Any mismatch between file extension and signature direction is a compile error
- A declaration body in `.funs` is a compile error
- A declaration without a body in `.morths` is a compile error
- `unsafe` inside `.morths` is a compile error

So these are valid:

```morph
// valid in .funs
ResolveViewer : ResolveViewerQuery -> ResolveViewerResult

// valid in .morths
ViewerToFeedQuery : ResolveViewerResult -> LoadFeedQuery = {
    Authenticated(id) -> LoadFeedQuery(ByUser(id))
    Anonymous(id)     -> LoadFeedQuery(BySession(id))
}
```

And these are invalid:

```morph
// invalid in .funs: wrong output kind and has body
ViewerToFeedQuery : ResolveViewerResult -> LoadFeedQuery = { ... }

// invalid in .morths: wrong direction
ResolveViewer : ResolveViewerQuery -> ResolveViewerResult
```

---

## Type Declarations

```ebnf
type_decl ::=
    primitive_type
  | named_type
  | product_type
  | sum_type
  | query_type
  | result_type
```

### Primitive VO

```ebnf
primitive_type ::= "type" TypeName "=" base_type ("where" constraint)?
```

```morph
type UserId  = UUID
type Email   = String where { it.contains("@") }
type Age     = Int where { it > 0 && it < 150 }
type PostId  = UUID
```

### Named VO

Named fields are allowed only in named value objects.

```ebnf
named_type ::= "type" TypeName "=" "{" vo_fields "}" ("where" constraint)?
vo_fields  ::= vo_field ("," vo_field)*
vo_field   ::= IDENT ":" TypeName
```

```morph
type DateRange = { start: Date, end: Date }
type GeoPoint  = { lat: Latitude, lon: Longitude }
```

### Product Type

```ebnf
product_type ::= "type" TypeName "=" "{" type_list "}"
```

```morph
type User = { UserId, Email, Age }
type Post = { PostId, Title }
```

### Sum Type

```ebnf
sum_type ::= "type" TypeName "=" variant ("|" variant)+
variant  ::= TypeName ("(" type_list ")")?
```

```morph
type ViewerRef = ByUser(UserId) | BySession(SessionId)
type FeedState = HasPosts(Posts) | EmptyFeed(Posts)
```

### Query Type

```ebnf
query_type ::= "type" TypeName ":" "Query" "=" "{" type_list "}"
```

```morph
type GetUserQuery   : Query = { UserId }
type GetPostsQuery  : Query = { ViewerRef }
type RenderQuery    : Query = { ViewerRef, Posts }
```

### Result Type

```ebnf
result_type ::= "type" TypeName ":" "Result" "=" variant ("|" variant)+
```

```morph
type GetUserResult : Result =
    Found(User)
  | NotFound(UserId)
  | ValidationFailed(ValidationError)
```

### Kind rules

- A `Query` type may only appear on the input side of `.funs` or the output side of `.morths`
- A `Result` type may only appear on the output side of `.funs` or the input side of `.morths`
- The compiler treats `Query` and `Result` as semantic type categories, not comments

---

## Constraints (`where`)

```ebnf
constraint ::= "{" bool_expr "}"
bool_expr  ::= expr (("&&" | "||") expr)*
expr       ::= "it" ("." IDENT)* cmp_op (literal | method_call)
cmp_op     ::= ">" | "<" | ">=" | "<=" | "==" | "!="
```

```morph
type Port     = Int where { it >= 1 && it <= 65535 }
type NonEmpty = String where { it.length > 0 }
```

Constraint is checked statically when the value is known at compile time.
Otherwise it is checked at construction time.

---

## Type Lists and Built-in Collections

```ebnf
type_list        ::= TypeName ("," TypeName)*
base_type        ::= Int | String | Float | Bool | UUID | TypeName
collection_type  ::= "List" "[" TypeName "]"
                   | "Set"  "[" TypeName "]"
                   | "Map"  "[" TypeName "," TypeName "]"
```

```morph
type Posts = List[Post]
type UserIds = Set[UserId]
type UserIndex = Map[UserId, User]
```

Collections are built-in generic containers.

---

## `.funs` - Function Signatures Only

`.funs` files declare only contracts for computation stages.
They do not contain bodies.

```ebnf
fun_sig ::= FunName ":" TypeName "->" TypeName
```

```morph
ResolveViewer : ResolveViewerQuery -> ResolveViewerResult
LoadFeed      : LoadFeedQuery -> LoadFeedResult
RenderFeed    : RenderFeedQuery -> RenderFeedResult
```

Rules:

- The left type must be a `Query`
- The right type must be a `Result`
- No body is allowed
- No `unsafe` is allowed
- Every `.funs` declaration must have exactly one implementation bound in `link`

`.funs` defines what a stage means, not how it runs.

---

## `.morths` - Self-Contained `Result -> Query` Declarations

`.morths` files declare complete morths.
A morth is a safe, exhaustive transition from a `Result` to the next `Query`.

```ebnf
morth_decl   ::= MorthName ":" TypeName "->" TypeName "=" "{" morth_arm+ "}"
morth_arm    ::= pattern "->" (morth_expr | morth_block)
morth_block  ::= "{" val_decl* morth_expr "}"
morth_expr   ::= morth_constructor | field_access | IDENT | literal
morth_constructor ::= TypeName "(" (morth_expr ("," morth_expr)*)? ")"
```

```morph
ViewerToFeedQuery : ResolveViewerResult -> LoadFeedQuery = {
    Authenticated(userId) -> LoadFeedQuery(ByUser(userId))
    Anonymous(sessionId)  -> LoadFeedQuery(BySession(sessionId))
}
```

Rules:

- The left type must be a `Result`
- The right type must be a `Query`
- The declaration must have a body
- The body must be exhaustive for every variant of the input `Result`
- Every arm must evaluate to the declared `Query` type
- `unsafe` is forbidden
- Raw primitive extraction through `.value` is forbidden
- Function calls are forbidden
- IO is forbidden
- `.morths` do not use `.impl`
- `.morths` do not appear in `link`

Design intent:

- a morth is simple and explicit
- a morth routes variants
- a morth prepares the next `Query`
- a morth is not a place for business logic, IO, or unsafe transformations

If a transformation needs `unsafe` or external effects, move it into a `.funs` stage.

---

## `.impl` - Implementations for `.funs`

`.impl` files contain implementations only for `.funs`.
They never implement `.morths`.

```ebnf
impl_decl     ::= "impl" ImplName dep_list? "=" (pure_block | unsafe_block)
dep_list      ::= "(" FunName ("," FunName)* ")"
pure_block    ::= "{" val_decl* match_expr "}"
unsafe_block  ::= "unsafe" "{" val_decl* unsafe_stmt* match_expr "}"
```

```morph
impl ResolveViewerDb = unsafe {
    val session = auth.lookup(it.RequestId)
    match session {
        KnownUser(id)    -> Authenticated(id)
        KnownGuest(id)   -> Anonymous(id)
    }
}

impl RenderFeedHtml = {
    match it {
        RenderFeedQuery(viewer, posts) -> Rendered(render(viewer, posts))
    }
}
```

Rules:

- The final expression of an `impl` must be a `Result`
- IO is allowed only inside `unsafe`
- `impl` targets only `.funs`
- A `.impl` declaration is not a pipeline stage by itself
- It becomes executable only when bound to a `.funs` declaration through `link`

---

## Expressions and Statements

```ebnf
val_decl      ::= "val" IDENT "=" expr
expr          ::= fun_call | constructor | field_access | literal | IDENT
fun_call      ::= FunName "(" expr ")"
constructor   ::= TypeName "(" (expr ("," expr)*)? ")"
field_access  ::= IDENT "." (TypeName | IDENT)
```

```morph
val result = ResolveViewer(ResolveViewerQuery(RequestId("abc")))

viewer.UserId
range.start
RenderFeedQuery(viewerRef, posts)
```

Field access rules:

- By type name for positional product fields
- By field name for named value objects only

---

## Pattern Matching

```ebnf
match_expr ::= "match" expr "{" match_arm+ "}"
match_arm  ::= pattern "->" (expr | block)
pattern    ::= TypeName ("(" IDENT ("," IDENT)* ")")? | "_"
block      ::= "{" val_decl* expr "}"
```

```morph
match result {
    Found(user)           -> SendWelcomeQuery(user.UserId)
    NotFound(id)          -> UserNotFound(id)
    ValidationFailed(err) -> InvalidUser(err)
}
```

Rules:

- Matches over `Result` and sum types must be exhaustive
- The compiler rejects non-exhaustive matches

The top-level body of a `.morths` declaration is structurally equivalent to an exhaustive match over the input `Result`.

---

## Pipeline (`|>`)

The pipeline operator `|>` composes declarations left to right.

```ebnf
pipeline_expr ::= pipeline_start ("|>" pipeline_step)+
pipeline_step ::= FunName | MorthName
```

Example:

```morph
ResolveViewerQuery(requestId)
    |> ResolveViewer
    |> ViewerToFeedQuery
    |> LoadFeed
    |> FeedToRenderQuery
    |> RenderFeed
```

Rules:

- A `.funs` stage always consumes a `Query` and produces a `Result`
- A `.morths` stage always consumes a `Result` and produces a `Query`
- A well-typed pipeline alternates these directions through type compatibility
- Two adjacent stages with incompatible kinds are a compile error
- Stage names are resolved from declarations in `.funs` and `.morths`
- `.funs` and `.morths` share one pipeline-stage namespace
- Duplicate stage names across `.funs` and `.morths` are a compile error
- No early-exit or short-circuit semantics are defined in this version

This version of the language models the pipeline as ordinary typed stage composition only.

---

## `link` - DI Graph for `.funs`

`link` binds function declarations from `.funs` to implementation declarations from `.impl`.
It does not bind `.morths`.

```ebnf
link_binding ::= FunName "->" ImplName
```

```morph
link {
    ResolveViewer -> ResolveViewerDb
    LoadFeed      -> LoadFeedStore
    RenderFeed    -> RenderFeedHtml
}
```

Compiler rules:

- Every `.funs` declaration must have exactly one bound `impl`
- Every `link` source must be a declaration from `.funs`
- Every `link` target must be a declaration from `.impl`
- A `.morths` declaration in `link` is a compile error
- The dependency graph over `.funs` implementations must be acyclic

So `link` remains the execution graph for `.funs`, while `.morths` are compiled directly as pure stage transitions.

---

## `entry`

The entry file contains the root pipeline.

```ebnf
entry_file ::= "entry" "=" unsafe_block
```

```morph
entry = unsafe {
    ResolveViewerQuery(RequestId("550e8400-e29b-41d4-a716-446655440000"))
        |> ResolveViewer
        |> ViewerToFeedQuery
        |> LoadFeed
        |> FeedToRenderQuery
        |> RenderFeed
}
```

`entry` is the top-level executable pipeline.

---

## Lexical Rules

```ebnf
TypeName   ::= [A-Z][a-zA-Z0-9]*
FunName    ::= [A-Z][a-zA-Z0-9]*
MorthName  ::= [A-Z][a-zA-Z0-9]*
ImplName   ::= [A-Z][a-zA-Z0-9]*
IDENT      ::= [a-z][a-zA-Z0-9_]*
literal    ::= INT | FLOAT | STRING | BOOL | UUID_LIT
```

Conventions enforced by the compiler:

- Uppercase first letter for types, variants, stage names, and impl names
- Lowercase first letter for local bindings
- `FunName` and `MorthName` share the callable pipeline namespace
- `ImplName` lives in a separate namespace

---

## File Layout Convention

| File extension | Contains | `unsafe` allowed | Notes |
|----------------|----------|------------------|-------|
| `.types`   | type declarations | No | Value objects, sums, `Query`, `Result` |
| `.funs`    | `Query -> Result` signatures | No | Contracts only, no bodies |
| `.morths`  | `Result -> Query` declarations with bodies | No | Pure, exhaustive, self-contained |
| `.impl`    | implementations for `.funs` | Yes | Only place business logic and IO live |
| `.link`    | binding graph from `.funs` to `.impl` | No | `.morths` never appear here |
| `.entry`   | root pipeline | Yes | Top-level executable composition |

The compiler enforces these boundaries.

Examples of file-level compile errors:

- a `.funs` declaration with a body
- a `.morths` declaration without a body
- a `.morths` declaration containing `unsafe`
- a `.morths` declaration with signature `Query -> Result`
- a `.funs` declaration with signature `Result -> Query`

---

## Compiler Guarantees

The compiler enforces:

1. File-role correctness - declarations are valid only in their designated file kinds
2. Signature-direction correctness - `.funs` are only `Query -> Result`, `.morths` are only `Result -> Query`
3. Kind correctness - `Query` and `Result` categories are checked semantically
4. Exhaustiveness - every `.morths` declaration handles every input `Result` variant
5. Query validity - every `.morths` arm constructs the declared `Query` type
6. Implementation completeness - every `.funs` declaration is bound to exactly one `impl`
7. Link correctness - only `.funs` may be bound through `link`
8. Unsafe containment - `unsafe` appears only in `.impl` and `.entry`
9. Pipeline type alignment - every `|>` boundary connects compatible stage types
10. Namespace clarity - callable names from `.funs` and `.morths` are unique

A program that compiles has a fully typed pipeline graph with explicit boundaries between:

- computation stages
- routing stages
- implementations
- execution wiring

---

## Full Example

```morph
// feed.types
type RequestId   = UUID
type UserId      = UUID
type SessionId   = UUID
type PostId      = UUID
type Html        = String
type RenderError = String

type Post  = { PostId, Title }
type Posts = List[Post]

type ViewerRef = ByUser(UserId) | BySession(SessionId)

type ResolveViewerQuery : Query = { RequestId }

type ResolveViewerResult : Result =
    Authenticated(UserId)
  | Anonymous(SessionId)

type LoadFeedQuery : Query = { ViewerRef }

type LoadFeedResult : Result =
    FeedLoaded(ViewerRef, Posts)
  | FeedEmpty(ViewerRef, Posts)

type RenderFeedQuery : Query = { ViewerRef, Posts }

type RenderFeedResult : Result =
    Rendered(Html)
  | RenderFailed(RenderError)

// feed.funs
ResolveViewer : ResolveViewerQuery -> ResolveViewerResult
LoadFeed      : LoadFeedQuery -> LoadFeedResult
RenderFeed    : RenderFeedQuery -> RenderFeedResult

// feed.morths
ViewerToFeedQuery : ResolveViewerResult -> LoadFeedQuery = {
    Authenticated(userId) -> LoadFeedQuery(ByUser(userId))
    Anonymous(sessionId)  -> LoadFeedQuery(BySession(sessionId))
}

FeedToRenderQuery : LoadFeedResult -> RenderFeedQuery = {
    FeedLoaded(viewer, posts) -> RenderFeedQuery(viewer, posts)
    FeedEmpty(viewer, posts)  -> RenderFeedQuery(viewer, posts)
}

// feed.impl
impl ResolveViewerDb = unsafe {
    val session = auth.lookup(it.RequestId)
    match session {
        KnownUser(id)  -> Authenticated(id)
        KnownGuest(id) -> Anonymous(id)
    }
}

impl LoadFeedStore = unsafe {
    val posts = feedStore.loadByViewer(it.ViewerRef)
    match posts {
        Loaded(viewer, list) -> FeedLoaded(viewer, list)
        Empty(viewer, list)  -> FeedEmpty(viewer, list)
    }
}

impl RenderFeedHtml = {
    match it {
        RenderFeedQuery(viewer, posts) -> Rendered(render(viewer, posts))
    }
}

// app.link
link {
    ResolveViewer -> ResolveViewerDb
    LoadFeed      -> LoadFeedStore
    RenderFeed    -> RenderFeedHtml
}

// app.entry
entry = unsafe {
    ResolveViewerQuery(RequestId("550e8400-e29b-41d4-a716-446655440000"))
        |> ResolveViewer
        |> ViewerToFeedQuery
        |> LoadFeed
        |> FeedToRenderQuery
        |> RenderFeed
}
```

This example shows the intended separation:

- `.funs` declares stage contracts
- `.impl` executes computation
- `.morths` routes results into the next query
- `.link` wires only `.funs`
- the pipeline composes both stage kinds explicitly

---

## Why This Version Is Simpler

Removing `fun` and `morth` keywords makes source declarations lighter, but keeps intent explicit through file boundaries.

The design stays readable because:

- `Q -> R` is still always computation
- `R -> Q` is still always transition
- the compiler enforces which file kind may contain which declaration kind
- `link` still makes execution wiring explicit
- `.morths` stays small, safe, and local

This produces a language where stage role is not a token.
It is a typed, file-level contract.

---

*Morph v0.2 - keywordless stage declarations with `.funs` and `.morths`. Subject to change as implementation evolves.*
