# Third party code and its terms

bench2key itself is under the MIT licence; see `LICENSE`. This repository carries **no third party
source at all** — everything below is fetched at build time or supplied by the person building.
What is kept here is only the changes, which are ours.

## jSMTLIB — Eclipse Public License 1.0

Copyright (c) David R. Cok. Upstream: <https://github.com/smtlib/jSMTLIB>.

The SMT-LIB front end parses with jSMTLIB. The build clones it at the pinned commit
`e4ce8d59b8a78d29ee034cb2a38d18508577df0e` (2026-06-24), applies `patches/jsmtlib.diff` and copies
`patches/jsmtlib-added/`, then compiles the result. Nothing of jSMTLIB is stored here.

The commit is pinned because a patch is only ever known to apply to one tree. That this recipe is
faithful has been checked rather than assumed: upstream at that commit, plus the patch, plus the
additions, reproduces the tree the tool was developed against byte for byte.

**Our changes to jSMTLIB are EPL-1.0, not MIT.** `patches/jsmtlib.diff` modifies six files and
`patches/jsmtlib-added/` adds eleven — three Java files for `declare-datatype` support and eight
SMT-LIB logic definitions. Under the EPL these are Contributions to an EPL program and stay under
the EPL, which is also what keeps the obligation satisfiable: the modified source is available, and
what was modified is stated.

The EPL is a file-level copyleft. It governs jSMTLIB's files and our changes to them; it does not
reach the separate files written for bench2key, which is why those can be MIT.

## The TPTP grammar — no stated terms

`TPTP.g4`, published at <https://tptp.org/UserDocs/TPTPLanguage/TPTP.g4>, carries no licence
statement, so no permission to redistribute it has been given. **No copy is kept here.** The build
downloads the published file and applies `tools/fragmentise.py`, which is ours and is MIT. Build
offline or against another release with `-Ptptp.grammar=/path/to/TPTP.g4`.

This matters beyond the source tree: a built jar contains a parser generated from that grammar. Any
binary distribution should say so, and the question is worth settling with the TPTP maintainers
before one is published.

## ANTLR 4 — BSD 3-Clause

Copyright (c) 2012-2017 The ANTLR Project. Resolved from Maven Central at build time. The tool
generates the TPTP parser and the runtime is linked into the result; attribution is all it asks.

## KeY — GNU General Public License, version 2

Copyright (c) the KeY project. Not fetched, not stored, not shipped: the person building supplies a
jar, and only if they want the in-process runner.

The default build does not compile against KeY, does not contain it and does not ship it. KeY is run
as a separate program, started as a subprocess, which the GPL does not restrict.

`-Pkey.inprocess=true` adds `src/keyLinked`, which does link KeY. **A build made that way must not
be distributed**: linking makes the result a combined work that would have to go out under the GPL,
and the GPL and the EPL cannot be combined, so jSMTLIB forbids it. The GPL governs distribution
rather than use, so such a build is fine to make and keep.

## Problem libraries

Neither the TPTP library nor the SMT-LIB benchmarks are stored here; they are downloaded by whoever
uses the tool. Note that a generated `.key` file is derived from the problem it came from, so
publishing a collection of translated problems raises a question this notice does not answer.
