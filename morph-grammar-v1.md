# Morph Language — Grammar Specification v0.1

> Every program is a graph of morphisms `Query → Result`.
> Types are objects. Functions are arrows. `link` is the category.

---

## Keywords

```
type  fun  impl  link  entry  unsafe  match  val  where  typeclass  Query  Result  it
```

13 keywords total.

---

## Program Structure

```ebnf
program     ::= file+
file        ::= types_file | funs_file | impl_file | link_file | entry_file

types_file  ::= type_decl+
funs_file   ::= fun_sig+
impl_file   ::= impl_decl+
link_file   ::= "link" "{" link_binding+ "}"
entry_file  ::= "entry" "=" unsafe_block
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
  | typeclass_decl
  | impl_typeclass
```

### Primitive VO (refined type)

```ebnf
primitive_type ::= "type" TypeName "=" base_type ("where" constraint)?
```

```morph
type Age   = Int    where { it > 0 && it < 150 }
type Email = String where { it.contains("@") }
type UserId = UUID
```

### Named VO (fields allowed — only here)

```ebnf
named_type ::= "type" TypeName "=" "{" vo_fields "}" ("where" constraint)?
vo_fields  ::= vo_field ("," vo_field)*
vo_field   ::= IDENT ":" TypeName
```

```morph
type DateRange = { start: Date, end: Date }
type Coordinates = { lat: Latitude, lon: Longitude }
```

> **Rule:** Named fields are allowed **only inside VO**. All other composite types use positional `type_list`.

### Product Type (no field names)

```ebnf
product_type ::= "type" TypeName "=" "{" type_list "}"
```

```morph
type User    = { UserId, Email, Age }
type Address = { Street, City, Country }
```

### Sum Type (ADT)

```ebnf
sum_type ::= "type" TypeName "=" variant ("|" variant)+
variant  ::= TypeName ("(" type_list ")")?
```

```morph
type Shape = Circle(Radius) | Rect(Width, Height) | Point
```

### Query Type

```ebnf
query_type ::= "type" TypeName ":" "Query" "=" "{" type_list "}"
```

```morph
type GetUserQuery    = { UserId }       : Query
type GetRangeQuery   = { DateRange }    : Query
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

---

## Constraints (`where` clauses)

```ebnf
constraint ::= "{" bool_expr "}"
bool_expr  ::= expr (("&&" | "||") expr)*
expr       ::= "it" ("." IDENT)* cmp_op (literal | method_call)
cmp_op     ::= ">" | "<" | ">=" | "<=" | "==" | "!="
```

```morph
type Port    = Int    where { it >= 1 && it <= 65535 }
type NonEmpty = String where { it.length > 0 }
```

Constraint is checked **statically** if the value is known at compile time. Otherwise checked at construction site.

---

## Type Lists

```ebnf
type_list ::= TypeName ("," TypeName)*
base_type ::= Int | String | Float | Bool | UUID | TypeName
```

---

## Function Signatures (`.funs` file)

```ebnf
fun_sig ::= "fun" FunName ":" TypeName "->" TypeName
```

```morph
fun FindUser   : GetUserQuery   -> GetUserResult
fun CreateUser : CreateUserQuery -> CreateUserResult
fun SendEmail  : SendEmailQuery  -> SendEmailResult
```

> **Rule:** Signature is a contract — no body. Always one `Query` in, one `Result` out.

---

## Implementations (`.impl` file)

```ebnf
impl_decl  ::= "impl" FunName dep_list? "=" (pure_block | unsafe_block)
dep_list   ::= "(" FunName ("," FunName)* ")"

pure_block   ::= "{" val_decl* match_expr "}"
unsafe_block ::= "unsafe" "{" val_decl* unsafe_stmt* match_expr "}"
```

```morph
// Pure implementation
impl ValidateUser = {
    val age = ...
    match age {
        Valid(a) -> UserValidated(a)
        _        -> ValidationFailed(AgeError)
    }
}

// Side-effectful implementation (DB, HTTP, IO)
impl FindUserImpl(FindUser) = unsafe {
    val row = db.query(GetUserQuery)
    match row {
        Some(r) -> Found(User(r.UserId, r.Email, r.Age))
        None    -> NotFound(query.UserId)
    }
}
```

> **Rule:** The last expression in any block must be a `Result`. IO is only permitted inside `unsafe`.

---

## Expressions & Statements

```ebnf
val_decl     ::= "val" IDENT "=" expr
expr         ::= fun_call | constructor | field_access | literal | IDENT
fun_call     ::= FunName "(" expr ")"
constructor  ::= TypeName "(" (expr ("," expr)*)? ")"
field_access ::= IDENT "." (TypeName | IDENT)
```

```morph
val result = FindUser(GetUserQuery(UserId("abc")))

// Field access — by type (product types)
user.Email
user.UserId

// Field access — by name (VO only)
range.start
range.end
```

---

## Pattern Matching

```ebnf
match_expr ::= "match" expr "{" match_arm+ "}"
match_arm  ::= pattern "->" (expr | block)
pattern    ::= TypeName ("(" IDENT ("," IDENT)* ")")? | "_"
```

```morph
match result {
    Found(u)              -> processUser(u)
    NotFound(id)          -> UserNotFoundError(id)
    ValidationFailed(err) -> PropagateError(err)
}
```

> **Rule:** Match over `Result` and `sum_type` must be **exhaustive**. Compiler rejects non-exhaustive matches.

---

## Result → Query Auto-Cast

```ebnf
cast_expr ::= expr "|>" TypeName
```

```morph
// Structural cast: if variant contains all types Query expects — cast is automatic
Found(user) |> GetPostsQuery

// If types don't align — compile error with hint
```

Type checker verifies that all types present in the `Result` variant are present in the target `Query`. Manual construction is always an alternative.

---

## Link (DI Graph)

```ebnf
link_file    ::= "link" "{" link_binding+ "}"
link_binding ::= FunName "->" FunName ("(" FunName ("," FunName)* ")")?
```

```morph
link {
    FindUser   -> FindUserImpl
    CreateUser -> CreateUserImpl(FindUser)
    SendEmail  -> SendEmailImpl
}
```

Compiler enforces:
- Every `fun` has **exactly one** `impl` bound
- The dependency graph is **acyclic** (topological sort at compile time)
- All injected dependencies are declared in `link`

---

## Type Classes

```ebnf
typeclass_decl ::= "typeclass" TypeName "[" TypeParam "]" "=" "{" fun_sig+ "}"
impl_typeclass ::= "impl" TypeName "[" TypeName "]" "=" "{" impl_decl+ "}"
```

```morph
typeclass Eq[T] = {
    fun Equals : (T, T) -> Bool
}

typeclass Printable[T] = {
    fun Print : T -> String
}

impl Eq[UserId] = {
    impl Equals = {
        match (a, b) {
            _ -> Bool(a.value == b.value)
        }
    }
}
```

> **v0.1 scope:** Type classes over concrete types only. No higher-kinded types (`F[A]`). Covers `Eq`, `Ord`, `Printable` — sufficient for v0.1.

---

## Collections (built-in)

```ebnf
collection_type ::= "List" "[" TypeName "]"
                  | "Set"  "[" TypeName "]"
                  | "Map"  "[" TypeName "," TypeName "]"
```

```morph
type UserList = List[User]
type UserIndex = Map[UserId, User]
```

Built-in methods: `map`, `filter`, `fold`, `find`. Collections are the only built-in generic containers in v0.1.

---

## Lexical Rules

```ebnf
TypeName  ::= [A-Z][a-zA-Z0-9]*       // Type, Variant, Function name
FunName   ::= [A-Z][a-zA-Z0-9]*       // Same namespace — context distinguishes
IDENT     ::= [a-z][a-zA-Z0-9_]*      // Bindings in match and val only
TypeParam ::= [A-Z]                    // Single uppercase — typeclass parameter
literal   ::= INT | FLOAT | STRING | BOOL | UUID_LIT
```

**Convention enforced by compiler:**
- Uppercase first letter → type, variant, or function name
- Lowercase first letter → local binding (`val`, `match` arm)

---

## File Layout Convention

| File extension | Contains         | IO allowed |
|----------------|------------------|------------|
| `.types`       | `type`, `typeclass` | No       |
| `.funs`        | `fun` signatures  | No        |
| `.impl`        | `impl` bodies     | Only in `unsafe` |
| `.link`        | `link` graph      | No        |
| `.entry`       | `entry` point     | Yes (always `unsafe`) |

> The compiler enforces these boundaries. A `.funs` file containing `unsafe` is a compile error.

---

## Full Example

```morph
// user.types
type UserId = UUID
type Email  = String where { it.contains("@") }
type Age    = Int    where { it > 0 && it < 150 }
type User   = { UserId, Email, Age }

type GetUserQuery   : Query  = { UserId }
type GetUserResult  : Result = Found(User) | NotFound(UserId)

// user.funs
fun FindUser : GetUserQuery -> GetUserResult

// user.impl
impl FindUserImpl = unsafe {
    val row = db.query(it.UserId)
    match row {
        Some(r) -> Found(User(r.UserId, r.Email, r.Age))
        None    -> NotFound(it.UserId)
    }
}

// app.link
link {
    FindUser -> FindUserImpl
}

// app.entry
entry = unsafe {
    FindUser(GetUserQuery(UserId("550e8400-e29b-41d4-a716-446655440000")))
}
```

---

*Morph v0.1 — grammar subject to change as implementation evolves.*
