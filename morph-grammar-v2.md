# Morph Language — Pipeline & Morphism System (v0.2 addition)

> Every program is a graph of morphisms `Query → Result`.
> `fun` transforms a Query into a Result.
> `morph` transforms a Result into the next Query.
> `|>` composes them into a pipeline.

---

## The Two Primitives of Transformation

Morph has exactly two transformation primitives:

| Primitive | Direction         | Role                                      |
|-----------|-------------------|-------------------------------------------|
| `fun`     | `Query → Result`  | Business logic, IO, computation           |
| `morph`   | `Result → Query`  | Transition between steps, variant routing |

A program is a sequence of alternating `fun` and `morph` calls connected by `|>`.

---

## Pipeline (`|>`)

The pipeline operator `|>` composes transformations left to right.
The pipeline is **always type-safe** — no raw type construction or deconstruction happens at the pipeline level.

```
GetUserQuery(id)
    |> FindUser
    |> UserToPostsQuery
    |> FindPosts
    |> PostsToRenderQuery
    |> RenderPosts
```

**Rules:**
- Each step receives the output type of the previous step
- `fun` always receives a `Query`, always returns a `Result`
- `morph` always receives a `Result`, always returns a `Query`
- The compiler verifies type compatibility at every `|>` boundary
- If types are incompatible at any boundary — **compile error**

---

## Morph Declaration (`Result → Query`)

`morph` is a named, exhaustive transition from a `Result` to the next `Query`.
It is the only place where variant routing is allowed in a pipeline.

```
morph_decl ::= "morph" MorphName ":" ResultType "->" QueryType "=" "{" morph_arm+ "}"
morph_arm  ::= variant_pattern "->" query_constructor
```

### Example

```
type GetUserResult : Result =
    Found(User)
  | NotFound(UserId)
  | ValidationFailed(ValidationError)

type GetPostsQuery : Query = { UserId }

morph UserToPostsQuery : GetUserResult -> GetPostsQuery = {
    Found(user)              -> GetPostsQuery(user.UserId)
    NotFound(id)             -> GetPostsQuery(DefaultUserId)
    ValidationFailed(err)    -> GetPostsQuery(FallbackUserId)
}
```

**Rules:**
- `morph` must be **exhaustive** — every variant of the input `Result` must be handled
- Non-exhaustive `morph` is a **compile error**
- The output must always be a valid `Query` — the type system enforces this
- Inside a `morph` arm, primitive extraction is allowed (see below)
- A `morph` may be `unsafe` if it requires IO to construct the next `Query`

---

## Primitive Extraction — `unsafe` Only

In Morph, all types are Value Objects. There are no raw primitives at the pipeline level.
Extracting the underlying primitive value from a VO is only permitted inside `unsafe` blocks.

```
// COMPILE ERROR — cannot access raw primitive in safe context
morph BadMorph : GetUserResult -> GetPostsQuery = {
    Found(user) -> GetPostsQuery(user.UserId.value)  // .value is unsafe
}

// CORRECT — extraction inside unsafe morph
morph UserToPostsQuery : GetUserResult -> GetPostsQuery = unsafe {
    Found(user) -> {
        val rawId = user.UserId.value   // primitive extraction allowed here
        val mapped = mapToExternal(rawId)
        GetPostsQuery(ExternalUserId(mapped))
    }
    NotFound(id) -> GetPostsQuery(DefaultUserId)
}
```

**Why this rule exists:**

Primitive extraction bypasses VO constraints. If `UserId` has a `where` clause,
extracting `.value` and passing it raw to another constructor skips that validation.
Marking this `unsafe` makes the boundary explicit — the programmer takes responsibility,
the compiler makes it visible.

**Rule summary:**

| Context         | VO construction | Primitive `.value` access |
|-----------------|-----------------|---------------------------|
| `fun` (safe)    | ✅ allowed       | ❌ compile error           |
| `morph` (safe)  | ✅ allowed       | ❌ compile error           |
| `unsafe` block  | ✅ allowed       | ✅ allowed                 |

---

## Pipeline — Full Example

```
// types
type UserId   = UUID
type Email    = String where { it.contains("@") }
type Age      = Int    where { it > 0 && it < 150 }
type User     = { UserId, Email, Age }
type PostId   = UUID
type Post     = { PostId, UserId, Title }

type GetUserQuery   : Query  = { UserId }
type GetUserResult  : Result = Found(User) | NotFound(UserId)

type GetPostsQuery  : Query  = { UserId }
type GetPostsResult : Result = PostsFound(List[Post]) | NoPosts(UserId)

type RenderQuery    : Query  = { List[Post] }
type RenderResult   : Result = Rendered(Html) | RenderFailed(RenderError)

// funs
fun FindUser  : GetUserQuery  -> GetUserResult
fun FindPosts : GetPostsQuery -> GetPostsResult
fun Render    : RenderQuery   -> RenderResult

// morphs
morph UserToPostsQuery : GetUserResult -> GetPostsQuery = {
    Found(user)   -> GetPostsQuery(user.UserId)
    NotFound(id)  -> GetPostsQuery(DefaultUserId)
}

morph PostsToRenderQuery : GetPostsResult -> RenderQuery = {
    PostsFound(posts) -> RenderQuery(posts)
    NoPosts(_)        -> RenderQuery(List[Post]())
}

// pipeline
entry = unsafe {
    GetUserQuery(UserId("550e8400-e29b-41d4-a716-446655440000"))
        |> FindUser
        |> UserToPostsQuery
        |> FindPosts
        |> PostsToRenderQuery
        |> Render
}
```

---

## Compiler Guarantees for Pipelines

The compiler enforces the following at every `|>` boundary:

1. **Type alignment** — output type of step N is compatible with input type of step N+1
2. **Exhaustiveness** — every `morph` covers all variants of its input `Result`
3. **VO integrity** — primitive `.value` access outside `unsafe` is rejected
4. **IO boundary** — `unsafe` steps are visible in the pipeline signature
5. **Acyclic link graph** — the `link` bindings for all `fun` and `morph` form a DAG

A pipeline that compiles is a pipeline that cannot produce unhandled states.

---

## Why `fun` and `morph` Are Separate

It would be possible to merge them into a single `fun : A -> B` primitive.
Morph intentionally does not do this.

Separating `fun` (Query → Result) from `morph` (Result → Query) makes the
**intent** of each step visible in the pipeline. A reader immediately knows:

- `fun` — this step does work (computation, IO, validation)
- `morph` — this step routes and transitions (no work, only structure)

This separation also allows the compiler to apply different rules to each:
`fun` may be `unsafe`, `morph` should prefer to be safe.
A `morph` that requires `unsafe` is a signal that the transition is doing too much.

---

*Morph v0.2 — pipeline system. Subject to change as implementation evolves.*
