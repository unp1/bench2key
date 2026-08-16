# bench2key

Translates benchmark problems into KeY `.key` problem files, and proves them with KeY.

Two input languages, SMT-LIB and TPTP, over one back end: the same corpus scanner, the same KeY
runner and the same window, which carries a tab per language.

## Building

You need a JDK (the build asks for a Java 21 toolchain), `git`, `python3`, and a network connection
the first time: the build fetches jSMTLIB and the TPTP grammar rather than storing them here.

```
./gradlew shadowJar
```

That is all. The build clones jSMTLIB at a pinned commit and applies our patch, downloads the TPTP
grammar and applies our one fix to it, generates the parser, and produces one self-contained file,
`build/libs/bench2key.jar`, with everything it needs inside.

Offline, or against another grammar release: `-Ptptp.grammar=/path/to/TPTP.g4`. jSMTLIB still needs
the network on a clean build; once fetched it is cached under `build/jsmtlib`.

KeY is **not** needed to build or to translate. It is needed only to prove, and then only as a jar
you supply, which is never bundled. See Licences below.

## The window

```
java -jar build/libs/bench2key.jar
```

Started with no arguments, the tool opens its window. There is a tab per input language, SMT-LIB and
TPTP, and each does the whole job in one place: point it at a collection, scan, translate, and prove
with KeY.

Working from the top of a tab:

**Source directory.** A directory or a `.zip` of one, so the TPTP library can stay compressed, about
900 MB against 10 GB unpacked. Press **Scan** and the left hand list fills with the collection's
logics, for SMT-LIB, or domains, for TPTP, with the problem count of each.

**Output directory**, where the generated `.key` files go, mirroring the structure of the input.

**KeY jar**, needed only for proving. Build one with `./gradlew :key.ui:shadowJar` in a KeY checkout.

**The options row** carries what that language's translation offers: how arrays are encoded and how
`let` is handled for SMT-LIB, where the axioms are for TPTP.

Select a logic or a domain and its problems appear in the table with their declared status. The
status filter matters: only `unsat` for SMT-LIB, and `Theorem`, `Unsatisfiable` or
`ContradictoryAxioms` for TPTP, leave a proof to find at all, and the note beside the filter says
so. **Translate selected** writes the `.key` files; **Prove selected** runs KeY over them and fills
in the proof, time and node columns. **Strategy settings** edits what KeY is told, either as the
default or for the selected problems alone. **View / edit** shows a problem beside its translation,
and **Open in KeY** hands a finished proof to KeY's own window.

Everything slow runs in the background, so the window stays responsive and **Stop** ends a long
batch. A problem read out of an archive is shown but not editable, since saving would rewrite the
archive.

## The command line

The same work without the window, for batches and scripts:

```
java -jar build/libs/bench2key.jar tptp --out /tmp/keyfiles TPTP-v9.3.0.zip
java -jar build/libs/bench2key.jar smt  --out /tmp/keyfiles problem.smt2
```

`--help` after `smt` or `tptp` lists the options of that tool. `./gradlew installDist` also produces
`build/install/bench2key/bin/bench2key`, a launcher that saves typing `java -jar`.

See how much of a collection translates, without writing anything:

```
java -jar build/libs/bench2key.jar check TPTP-v9.3.0.zip
```

Three sets in about two minutes: one problem of each language, ten of every domain, and the twelve
largest. That is the quickest honest answer to "does this still work"; a full pass takes hours and
buys only precision.

To write KeY strategy settings into the generated files so the prover honours them on load:

```
... tptp --arith defops --strategy-timeout 60000 --max-steps 10000000 --out /tmp problem.p
```

Two things that will otherwise cost you an hour. KeY's `--timeout` on the command line is silently
overridden by the `Timeout` in a file's `\settings` block, so the limit has to go in the file, which
is what `--strategy-timeout` does. And KeY's default of 30,000 rule applications binds long before
any sensible time limit, so raise it or you are measuring the step cap.

## Direction of the translation

The two languages ask different questions, so they reach a KeY sequent differently.

An SMT solver asks whether the assertions are satisfiable together, while KeY asks whether a
sequent is valid. The two line up on `assertions ==> false`, which is valid exactly when the
assertions are unsatisfiable, so a closed proof means `unsat`.

A TPTP problem already asks whether its conjecture follows from its axioms, which is what a KeY
sequent asks, so it needs no such detour: `axioms ==> conjecture`, and a closed proof means the
problem is a `Theorem`. CNF problems have no conjecture, only clauses whose satisfiability is in
question, so there it is `clauses ==> false` again, and a closed proof means `Unsatisfiable`.

Either way, a problem whose declared status is satisfiable has no closed proof to find, and the
status filter is the way to leave those out of a run.

## How the two languages are kept apart

Everything language-specific is reached through `Format` in `org.key_project.bench2key.run`: what a
source file is, how to read a problem's category and declared status out of it, which statuses can
be proved, and how to translate one file. A `Format` is typed on its own settings record, so the
two languages cannot be handed each other's options.

`FormatUi` in `org.key_project.bench2key.gui` adds the part only a window needs: the controls for
those settings. `TranslatorPanel` is the window body, written once, and each tab is one instance of
it. The corpus scanner, the KeY runners, the problem table, the statistics and the strategy dialog
know nothing about either language.

Adding a third language means writing a `Format`, a `FormatUi`, and a line in `MainWindow`.

## Per language

`org.key_project.smt2key` and `org.key_project.tptp2key` each hold their own translator, their own
command line front end and their own `Format`. Their READMEs cover what each translation does and
does not handle:

- SMT-LIB: uninterpreted sorts and functions, Core, integer arithmetic, quantifiers, `let`, `ite`,
  `define-fun`, `push`/`pop`, and arrays under three encodings. See `docs/smt2key.md`.
- TPTP: CNF, FOF and TFF without polymorphism, including includes, distinct objects and integer
  arithmetic. See `docs/tptp2key.md`.

## Running KeY on the output

```
java -Djava.awt.headless=true -Xss512m -Dkey.home=<a directory of its own> \
     -jar key-exe.jar --auto-loadonly problem.key
```

All three flags matter for batch work. Without `-Djava.awt.headless=true` each run opens a dock
icon on macOS and takes the focus, though that only reduces the problem rather than removing it:
`ViewSettings` in `key.core` initialises `UIManager` in a static field, so the AWT toolkit loads on
the headless path too. Without a large `-Xss`, the deeply nested formulas of domains such as `CSR`
overflow KeY's own parser stack while loading, which is reported as a failed load rather than as a
stack overflow. Pinning `key.home` keeps a run from picking up settings left in `~/.key` by
another one, which can silently change the taclet options a problem is read with.

## Checking a collection

```
bench2key check TPTP-v9.3.0/Problems --tptp TPTP-v9.3.0
```

Three sets, about two minutes against the whole library, where running everything takes hours:

| set | what it is | what it is for |
| --- | --- | --- |
| `smoke` | one problem of each language | a change that breaks a language says so at once |
| `spread` | ten problems of every domain | a coverage figure to a few percent, and per domain regressions |
| `extremes` | the twelve largest problems | faults of size and depth, which is where the crashes were |

The single figure a full run produces is the translated fraction to within a fraction of a percent.
A spread gives the same figure to a few percent in half a minute, and reaches every corner of the
library at once, which a run in alphabetical order does not: the spread found the `SYN` syntax
problems in thirty seconds where a full run had not reached them in hours. The extremes matter for
the opposite reason: faults of size and depth are not spread evenly through a library, they sit at
its edges. Both crashes found so far were there, and half of that set still reports out of reach.

Run the whole library, with `bench2key tptp --sample all`, when a number is going to be published,
once, against a build that is not changing under it.

## Archives

A collection can be a directory or a `.zip` of one, and everything works the same either way:
scanning, reading a problem's header, resolving the `include` directives that reach the axiom
files, and the window's file chooser. Extracted, the TPTP library is about ten gigabytes; zipped it
is nearer one, and there is no reason to keep the loose copy.

```
cd TPTP-v9.3.0 && zip -q -r ../TPTP-v9.3.0.zip .
bench2key tptp --within Problems --out /tmp TPTP-v9.3.0.zip
```

Problems and axioms are named separately, because they are different things: a scan collects
problems, while the axiom files are what problems include. An `Axioms` directory of the collection
is left out of a scan on its own, in the window and on the command line alike, so pointing at an
archive of the whole library yields its problems and nothing else. The window's `Axioms` field, and
`--tptp` on the command line, name that place when it is somewhere else or has another name.

`--within` narrows a scan further, to one domain say, while includes still resolve against the whole
collection. An archive holding a single directory at its top is entered, so pointing at one made
from `TPTP-v9.3.0/` behaves like pointing at the directory.

Reading from a zip costs nothing measurable: the PUZ domain translates in 14.3 s from an archive
against 15.9 s from a directory, and the output is byte for byte the same. A problem read out of an
archive is shown in the window but cannot be edited there, since saving would rewrite the archive.

A `.tgz`, which is how the library is published, is not supported and is not worth supporting:
reading one file out of a gzipped tar means decompressing everything before it, so picking a single
problem would cost a pass over the whole archive. Unpack it once and zip it.

## Size

Nothing is refused for being large. A parse tree costs far more memory than the text it came from,
and both libraries hold problems of several hundred megabytes, TPTP's `Axioms/CSR002+5.ax` being
477 MB, so a run that reaches those wants a large heap:

```
JAVA_OPTS=-Xmx32g build/install/bench2key/bin/bench2key tptp ...
```

The launcher asks for 8 GB on its own and `JAVA_OPTS` overrides it. A run that runs out anyway
reports that file as out of reach and carries on with the next one. `--max-size` is there for
surveys that would rather skip the giants than wait for them, and is off unless asked for.

## Licences

The default build links nothing under a copyleft licence, and that is deliberate.

bench2key itself is under the **MIT licence**; see `LICENSE`, and `THIRD-PARTY.md` for the terms of
everything else.

| Component | Licence | How it is used |
| --- | --- | --- |
| bench2key | MIT | — |
| jSMTLIB | EPL-1.0 | fetched at a pinned commit and patched; nothing of it stored here |
| ANTLR 4 runtime and tool | BSD-3-Clause | linked, and at build time |
| the TPTP grammar | none stated upstream | fetched at build time, never redistributed here |
| KeY | GPL v2 | started as a separate program; linked only in an opt-in build |

Two things drive this. KeY is GPL v2 and jSMTLIB is EPL-1.0, and those two cannot be combined in
one distributed program, so KeY is reached by starting it as a program of its own, which raises no
such question. And the grammar published at tptp.org carries no licence statement, so there is no
permission to redistribute it, which is why the build fetches it instead of this repository
carrying a copy.

The in-process proof runner does link KeY, so it lives in `src/keyLinked` and is compiled only when
asked for:

```
./gradlew -Pkey.inprocess=true installDist
```

Without that flag nothing compiles against KeY, `InProcessRunner` is not in the jar, the
distribution does not contain KeY, and the window offers only the subprocess runner. `KeyRunner`
finds the in-process one by name at runtime, which is what keeps the main code free of it. The GPL
governs distribution rather than use, so a build somebody makes and keeps is their own business;
handing one on would mean distributing the whole under the GPL, which the EPL part forbids.

MIT is the most permissive licence available here, and nothing in the dependencies forces copyleft
onto this code: EPL-1.0 is a file-level copyleft that keeps jSMTLIB's own files, and our changes to
them, under the EPL without reaching separate files, and ANTLR is BSD. MIT is also compatible with the GPL in both
directions, which Apache-2.0 would not have been; that keeps the opt-in build from being ruled out
on our side, though jSMTLIB's EPL still rules it out on its own. A built jar carries jSMTLIB's EPL
classes and a parser generated from a grammar whose terms upstream never stated, so any binary
distribution needs `THIRD-PARTY.md` with it.

## What is carried along

Nothing of anyone else's. `patches/` holds our changes to jSMTLIB — `jsmtlib.diff` for the six
files it modifies and `jsmtlib-added/` for the eleven it adds — together with three patches against
KeY that the SMT-LIB work needed. `tools/fragmentise.py` is the one change the fetched TPTP grammar
needs. `scanner/` is the coverage scanner. Everything else in the build is fetched at build time or
supplied by whoever is building.
