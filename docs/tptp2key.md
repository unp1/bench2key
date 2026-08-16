# The TPTP translation

What `bench2key tptp` and the TPTP tab do with a TPTP problem.

## Direction of the translation

A TPTP problem asks whether its conjecture follows from its axioms, and a KeY sequent asks the
same thing, so the two line up without a detour:

```
\problem { axioms ==> conjecture }
```

A closed KeY proof therefore means the problem is a `Theorem`. CNF problems have no conjecture,
only clauses whose satisfiability is in question, so there the sequent is

```
\problem { clauses ==> false }
```

which is valid exactly when the clause set is unsatisfiable. A closed proof there means
`Unsatisfiable`. Problems whose declared status is `Satisfiable` or `CounterSatisfiable` have no
closed proof to find, so `--status Theorem,Unsatisfiable` is the useful filter for a corpus run.

Unlike SMT-LIB, TPTP keeps formulas and terms apart in the same way KeY does, so nothing has to be
bridged between the two.

## Running

```
bench2key tptp --tptp /path/to/TPTP-v9.3.0 --out /tmp problem.p
```

`bench2key tptp --help` lists the options. A directory argument is walked for `*.p` and `*.ax`, and
so is a `.zip` of one; `--within Problems` scans part of a collection while includes still resolve
against all of it.



## What is covered

CNF, FOF, and TFF without polymorphism: uninterpreted sorts, functions and predicates, equality,
all the connectives, quantifiers, distinct objects, includes with and without a selection list, and
integer arithmetic.

Not covered, and refused by name rather than mistranslated: THF, which is higher order; TXF, which
puts formulas in argument positions; the modal and non-classical extensions; polymorphic TF1 types;
and `$rat` and `$real`, because KeY declares a `real` sort but ships no rules for it, so those
problems could be printed but never closed.

## Sorts

Untyped TPTP has one universe, which becomes one sort, `TPTP_i`. TFF's `$i` is that same sort, a
type declared with `$tType` becomes a sort of its own, and `$int` is KeY's own `int` with all of
KeY's integer rules behind it.

## Arithmetic

`$sum`, `$difference`, `$product` and `$uminus` map to KeY's arithmetic, and the comparisons to
`<`, `<=`, `>` and `>=`. `$quotient_e` and `$remainder_e` map to KeY's `div` and `mod`, which are
Euclidean in the same sense: `a = b * div(a,b) + mod(a,b)` with `0 <= mod(a,b) < |b|`. The
truncating and flooring variants have no counterpart and are refused. On integers `$floor`,
`$ceiling`, `$truncate`, `$round` and `$to_int` are the identity, and `$is_int` and `$is_rat` hold.

## Numerals and distinct objects

A distinct object becomes a `\unique` constant, which is what makes it differ from every other one.
In an untyped problem there is no arithmetic to do and TPTP asks only that numerals differ from
each other, so a numeral there becomes a unique constant as well. In a typed problem a numeral is
KeY's own integer literal.

## Naming

Function and predicate symbols get the prefix `tptp_`, sorts get `TPTP_`, and every character
outside `[A-Za-z0-9_]` becomes `_` plus a hex code, so a single quoted symbol such as `'a symbol'`
comes out as `tptp_a_20symbol`. The prefixes matter: redeclaring a name that KeY's standard rule
base already uses is a hard error, and that rule base occupies short names such as `add`, `select`
and `min`. Distinct TPTP symbols always get distinct identifiers. Quantified variables are renamed
to `sv_N`, so shadowing in the input cannot turn into variable capture in the output.

## Roles

`axiom`, `hypothesis`, `definition`, `assumption`, `lemma`, `theorem`, `corollary` and
`negated_conjecture` go to the antecedent; `conjecture` goes to the succedent; `type` is a
declaration. A problem with more than one conjecture proves their conjunction, since TPTP asks for
all of them while several formulas in a succedent would read as their disjunction. Any other role,
including `plain`, is refused: `plain` has no stated meaning and does not occur in the problem
library, so reading it as an axiom would be a guess that changes what is being proved.

## The grammar

The parser is generated at build time from the grammar the TPTP maintainers publish at
<https://tptp.org/UserDocs/TPTPLanguage/TPTP.g4>. No copy of it is kept in the repository, because
it carries no licence statement and so gives no permission to redistribute it; the build downloads
it and applies `tools/fragmentise.py`, which is ours. Build offline or against a different release
with `-Ptptp.grammar=/path/to/TPTP.g4`.

That one change is needed because the published grammar is generated from the BNF, where the rules
that produce tokens and the rules that define the character classes building them are written the
same way. ANTLR treats every unmarked lexer rule as a token it may return, and returns the first
rule of the longest match, so `3` comes back as `Exp_integer` rather than `Integer` and no parser
rule accepts it: without the change, no numeral anywhere in TPTP parses. The script marks every
lexer rule that no parser rule mentions as a `fragment`, which is what such a rule is.

Reading a problem runs on a thread with a 512 MB stack, settable with `--stack`. A generated parser
descends one frame per grammar rule, the collector and the translator descend once per level of
nesting as well, and the TPTP rules chain a formula through a dozen rules per level, which the
deeply nested problems in the `HWV` domain overflow on a default stack.
