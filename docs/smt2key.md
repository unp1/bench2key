# The SMT-LIB translation

What `bench2key smt` and the SMT-LIB tab do with an SMT-LIB benchmark.

## Direction of the translation

An SMT solver asks whether the assertions are satisfiable together; KeY asks whether a sequent is
valid. The two line up on

```
\problem { A1, ..., An ==> false }
```

which is valid exactly when the conjunction of the assertions is unsatisfiable. A closed KeY proof
therefore means `unsat`. A benchmark whose declared status is `sat` has no closed proof to find, so
only the `unsat` part of a benchmark collection is a meaningful target.

## Running

```
bench2key smt --out /tmp problem.smt2
```

`bench2key smt --help` lists the options. A directory argument is walked for `*.smt2`.

## What is covered

Uninterpreted sorts, functions and predicates; the Core theory; integer arithmetic, linear and
non-linear; quantifiers; `let`; `ite`; `define-fun`; `push`/`pop`; arrays. Not covered: reals,
bit-vectors, floating point, strings, datatypes, sort constructors of non-zero arity, and indexed
identifiers such as `(_ divisible n)`.

`div` and `mod` map to KeY's `div` and `mod`, which are Euclidean in the same sense as SMT-LIB:
`a = b * div(a,b) + mod(a,b)` with `0 <= mod(a,b) < |b|`.

## The three array encodings

`--arrays axioms` introduces a fresh sort per index/element pair, declares `select` and `store` over
it, and states read-over-write, read-over-write-with-different-index, and (unless `--no-ext`)
extensionality in the antecedent. This is faithful to SMT-LIB's ArraysEx for any index and element
sorts, but KeY has no array-specific rules to work with and has to use the axioms.

`--arrays heap` maps an SMT array to a KeY `Heap`, addressing index `i` as the field `arr(i)` of one
fixed object. KeY's own read-over-write taclets then apply and no axioms are emitted, since `arr` is
declared unique and supplies the disequality of distinct indices. Requires an `Int` index. KeY heaps
are not extensional, so a problem that needs array extensionality can stay open.

`--arrays seq` maps an SMT array to a KeY `Seq`, with `seqGet` and `seqUpd`. A `Seq` has a finite
length while an SMT array is total, and `seqUpd` outside that length is the identity, so this is
sound only where all indices are in range, which the translation does not check. Measured on three
basic array facts, it loses two of them: read-over-write at the same index is not a theorem about
sequences.

## Bool

KeY separates formulas from terms; SMT-LIB has `Bool` as an ordinary sort. Bool-valued declared
symbols become predicates. Where the two sides meet, the translation bridges: a formula in a term
position becomes `\if(F)\then(TRUE)\else(FALSE)`, and a boolean term in a formula position becomes
`t = TRUE`. Nothing is duplicated, and `Bool` arguments and `Bool`-sorted quantified variables both
work.

## Naming

Function and predicate symbols get the prefix `smt_`, sorts get `SMT_`, and every character outside
`[A-Za-z0-9_]` becomes `_` plus a hex code. The prefixes matter: redeclaring a name that KeY's
standard rule base already uses is a hard error, and that rule base occupies short names such as
`add`, `select` and `min`. Distinct SMT-LIB symbols always get distinct identifiers. Quantified
variables are renamed to `sv_N`, so SMT-LIB shadowing cannot turn into variable capture.

## Known limits

`let` is inlined, per its bound expression and re-translated at each use so that a variable used both
as a formula and as a term comes out right in each place. Scripts built as one deeply shared DAG,
which is how the SV-COMP families are written, blow up under inlining; the translation gives up at 64
MB of output rather than exhausting memory. On the 2025 SMT-LIB release this affects 132 of 10227
files. Introducing a symbol per binding instead would remove the limit.

Sorts are computed from the declarations and the enclosing scope rather than read from jSMTLIB's type
checker map. That map is keyed by expression and jSMTLIB compares symbols by name, so all occurrences
of one name share a single entry; scripts that reuse a `let` variable such as `_cse0` at several
sorts read back whichever sort was recorded last. The type checker still runs, and its diagnostics
still gate the translation.
