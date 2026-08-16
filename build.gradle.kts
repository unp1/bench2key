/*
 * bench2key: benchmark problems to KeY problem files, as a command line tool and a GUI.
 *
 * Two front ends, SMT-LIB and TPTP, over one back end: the same corpus scanner, the same KeY
 * runner and the same window, which carries a tab per input language.
 *
 * A single project rather than several, because the pieces it compiles are not separately
 * publishable: the patched jSMTLIB has no artifact of its own, the TPTP parser is generated at
 * build time from a grammar this repository does not carry, and KeY is taken from a checkout.
 * The SMT-LIB logic definitions are compiled in as resources, so the tool finds them without
 * being told where they are.
 */
import java.net.URI

plugins {
    java
    antlr
    application
    // Builds the self-contained jar the GUI is normally started from.
    id("com.gradleup.shadow") version "9.0.0"
}

version = "0.1"

repositories {
    mavenCentral()
}

val parserPackage = "org.key_project.tptp2key.parser"

/*
 * jSMTLIB is fetched and patched rather than kept here.
 *
 * The SMT-LIB front end parses with jSMTLIB, which needs six files changed and eleven added before
 * it serves. Keeping a copy of it in this repository would mean redistributing someone else's EPL
 * licensed work; keeping only the changes means the repository carries what is ours and the build
 * fetches the rest. The commit is pinned because a patch is only known to apply to one tree, and
 * this one is verified: upstream at that commit, plus `patches/jsmtlib.diff`, plus
 * `patches/jsmtlib-added`, reproduces the tree the tool was developed against byte for byte.
 */
val jsmtlibRepo = "https://github.com/smtlib/jSMTLIB.git"
val jsmtlibCommit = "e4ce8d59b8a78d29ee034cb2a38d18508577df0e"   // 2026-06-24
val jsmtlibDir = layout.buildDirectory.dir("jsmtlib").get().asFile

val prepareJsmtlib = tasks.register("prepareJsmtlib") {
    group = "build"
    description = "Fetches jSMTLIB at the pinned commit and applies our patch and additions."
    val marker = File(jsmtlibDir, ".prepared-$jsmtlibCommit")
    inputs.file("patches/jsmtlib.diff")
    inputs.dir("patches/jsmtlib-added")
    outputs.file(marker)
    doLast {
        if (jsmtlibDir.exists()) {
            jsmtlibDir.deleteRecursively()
        }
        fun git(workingDir: File?, vararg args: String) {
            val process = ProcessBuilder("git", *args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                throw GradleException("git " + args.joinToString(" ") + " failed:\n" + output)
            }
        }
        // A blobless clone: the history is not wanted, only this one tree.
        git(null, "clone", "--quiet", "--filter=blob:none", "--no-checkout",
            jsmtlibRepo, jsmtlibDir.absolutePath)
        git(jsmtlibDir, "checkout", "--quiet", jsmtlibCommit)
        git(jsmtlibDir, "apply", file("patches/jsmtlib.diff").absolutePath)
        copy {
            from("patches/jsmtlib-added/SMT")
            into(File(jsmtlibDir, "SMT"))
        }
        marker.writeText("prepared from $jsmtlibRepo at $jsmtlibCommit\n")
    }
}

sourceSets {
    main {
        java {
            srcDirs("src/main/java", "scanner/src", File(jsmtlibDir, "SMT/src"))
            // jSMTLIB ships a demonstration main in the default package that wants a solver.
            exclude("APIExample.java")
        }
        resources {
            srcDirs(File(jsmtlibDir, "SMT/logics"))
        }
    }
}

tasks.named("compileJava") { dependsOn(prepareJsmtlib) }
tasks.named("processResources") { dependsOn(prepareJsmtlib) }

/*
 * KeY is linked only when the build is asked for it.
 *
 * KeY is under the GPL and jSMTLIB, which the SMT-LIB front end parses with, is under the EPL; the
 * two cannot be combined in one distributed program. Calling KeY as a separate program raises no
 * such question, so that is what is built by default, and nothing here compiles against KeY at all.
 * `-Pkey.inprocess=true` adds the in-process runner, which does link KeY. The GPL governs
 * distribution rather than use, so such a build is a person's own business as long as they keep it;
 * distributing one means distributing the whole under the GPL, which the EPL part forbids.
 */
val inProcess = providers.gradleProperty("key.inprocess").orNull == "true"

/** KeY, taken from the checkout beside this one, or from $KEY_JAR. */
val keyJar = files(
    fileTree("../key/key.ui/build/libs") { include("*-exe.jar") },
    System.getenv("KEY_JAR") ?: emptyList<String>())

/**
 * KeY sits on a configuration of its own rather than on `implementation`.
 *
 * It is a shadow jar carrying a JUnit platform of its own, and a second JUnit platform on the test
 * runtime classpath stops the launcher from discovering any test at all.
 */
val key: Configuration = configurations.create("key")

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    if (inProcess) {
        key(keyJar)
    }
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/*
 * The in-process runner lives in a source set of its own, so that the default build neither
 * compiles nor ships it. When it is asked for, its output is folded into the main one, and
 * `KeyRunner` finds it by name at runtime.
 */
if (inProcess) {
    val keyLinked = sourceSets.create("keyLinked") {
        java.setSrcDirs(listOf("src/keyLinked/java"))
        // It calls into the main code and into KeY; nothing in main calls back, which is what
        // lets the main code compile with no KeY on its classpath at all.
        compileClasspath += sourceSets.main.get().output + key
    }
    tasks.named<Jar>("jar") { from(keyLinked.output) }
    sourceSets.main { runtimeClasspath += keyLinked.output + key }
}

if (inProcess) {
    tasks.named<CreateStartScripts>("startScripts") {
        classpath = (classpath ?: files()) + key
    }
    tasks.named<JavaExec>("run") {
        classpath += sourceSets["keyLinked"].output + key
    }
    // The start script names the jar, so the distribution has to carry it, or an installed copy
    // fails at the first proof. Such a distribution contains KeY and is nobody's to hand on.
    distributions.named("main") {
        contents {
            from(key) { into("lib") }
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "org.key_project.bench2key.Bench2Key"
    // A parse tree costs far more memory than the text it came from, and both libraries hold
    // problems of several hundred megabytes. JAVA_OPTS overrides this.
    applicationDefaultJvmArgs = listOf("-Xmx8g")
}

/*
 * The TPTP grammar is fetched rather than kept here.
 *
 * The grammar published at tptp.org carries no licence statement, so there is no permission to
 * redistribute it, let alone a modified copy. Nothing of it is therefore in this repository: the
 * build downloads the published file and applies `tools/fragmentise.py`, which is ours, so what is
 * kept here is the change and not the work it changes. Building offline, or against a different
 * release, is `-Ptptp.grammar=/path/to/TPTP.g4`.
 */
val grammarUrl = "https://tptp.org/UserDocs/TPTPLanguage/TPTP.g4"
val grammarDir = layout.buildDirectory.dir("grammar")
// The upstream copy is kept out of the directory ANTLR reads, which generates from
// every .g4 it finds there and rejects one whose file name is not its grammar name.
val upstreamGrammar = layout.buildDirectory.file("grammar-upstream/TPTP-upstream.g4")
val patchedGrammar = grammarDir.map { it.file("TPTP.g4") }

val fetchGrammar = tasks.register("fetchGrammar") {
    group = "build"
    description = "Downloads the TPTP grammar published at tptp.org."
    val local = providers.gradleProperty("tptp.grammar").orNull
    val target = upstreamGrammar
    inputs.property("source", local ?: grammarUrl)
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        if (local != null) {
            file.writeBytes(File(local).readBytes())
        } else {
            URI(grammarUrl).toURL().openStream().use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }
}

/**
 * Marks the grammar's helper lexer rules as fragments.
 *
 * The published grammar is generated from the BNF, where the rules that produce tokens and the
 * character class rules that build them are written the same way. ANTLR returns the first rule of
 * the longest match, so `3` comes back as Exp_integer rather than Integer and no parser rule
 * accepts it: without this, no numeral anywhere in TPTP parses.
 */
val patchGrammar = tasks.register<Exec>("patchGrammar") {
    dependsOn(fetchGrammar)
    inputs.file(upstreamGrammar)
    inputs.file("tools/fragmentise.py")
    outputs.file(patchedGrammar)
    commandLine("python3", "tools/fragmentise.py",
        upstreamGrammar.get().asFile.absolutePath, patchedGrammar.get().asFile.absolutePath)
}

sourceSets.main {
    antlr.setSrcDirs(listOf(grammarDir))
}

tasks.generateGrammarSource {
    dependsOn(patchGrammar)
    // The ANTLR plugin passes -package but writes to the root of its output directory, and javac
    // wants the directory to match the package.
    arguments = arguments + listOf("-package", parserPackage, "-visitor", "-no-listener")
    outputDirectory = layout.buildDirectory
        .dir("generated-src/antlr/main/" + parserPackage.replace('.', '/')).get().asFile
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // jSMTLIB is old code carried along unchanged; its warnings are not ours to act on.
    options.compilerArgs.add("-nowarn")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // The window test paints itself into this file when asked; the tests run in a JVM of their own,
    // which does not inherit the property from the build.
    System.getProperty("bench2key.screenshot")
        ?.let { systemProperty("bench2key.screenshot", it) }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes("Main-Class" to "org.key_project.bench2key.Bench2Key")
    }
}

/*
 * A built jar carries jSMTLIB's EPL classes and a parser generated from the TPTP grammar, so the
 * licence and the notice that says whose terms apply to what travel inside every jar.
 */
tasks.withType<Jar>().configureEach {
    from(rootDir) {
        include("LICENSE", "THIRD-PARTY.md")
        into("META-INF")
    }
}

/*
 * The shadow jar is how the tool is normally run: one file, no classpath to assemble, and the GUI
 * opens when it is started with no arguments. KeY is not in it unless the build was asked to link
 * KeY, which is a build nobody should hand on.
 */
tasks.shadowJar {
    // A name of its own, so it neither collides with the plain jar nor carries a version in the
    // command people are told to type.
    archiveFileName = "bench2key.jar"
    manifest {
        attributes("Main-Class" to "org.key_project.bench2key.Bench2Key")
    }
    mergeServiceFiles()
}

tasks.named("build") { dependsOn(tasks.shadowJar) }

