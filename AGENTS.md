# ModLoader

Minecraft 26.3+ (Mojang names, unobfuscated), Java 25. Root package `net.ccbluex.modloader`.

| Module | Runs in | Holds |
|---|---|---|
| `cli/` | the CLI's JVM; `agent.Agent` in the game's system class loader | commands and attach; builds `modloader.jar` with the modules below under `lib/` |
| `bridge/` | the game's bootstrap class path | `Bridge.link`, the bootstrap method of every call the compiler writes into a class |
| `loader/` | a class loader below the game's | `ModLoader` (the commands), `Mod`, `ModClassLoader`, the `Framework` and `ModFile` contract; in `mixin`: `MixinManager` (redefines and reverts), `Linker` (links the Bridge calls), the `MixinCompiler` contract |
| `api/` | same | what mods compile against |
| `framework/<name>/` | same | one `Framework` and `ModFile` per mod framework, found through `META-INF/services` |
| `mixin/` | a `CompilerClassLoader`, new for every compilation, with the bundled Mixin, MixinExtras and ASM | `Compiler`: `MixinSession` runs Mixin, `Outliner` splits a mixed class into redefinition and companion, `Rewriter` hands instructions to `Linker` |
| `example/` | a mod class loader | a mod with an entrypoint for every framework and a mixin; the tests load it |

A new framework is a module under `framework/`, its name in `settings.gradle.kts` and its `runtime(...)` line in
`cli/build.gradle.kts`.

## Commands

```sh
flock /tmp/modloader-gradle.lock ./gradlew build   # compiles and runs the tests
```

`cli`'s tests run the built jar against a stand-in game: `cli/src/test/java/net/minecraft` is that game,
`cli/src/mod` the mod with mixins they load, `cli/src/widened` what that mod compiles against in place of an
access widener.

Against real games: install 26.3 dedicated servers (vanilla `server.jar`, Fabric server launcher, NeoForge and Forge
installers with `--installServer`), start each with `-Xmx` and `eula=true`, then
`java -jar cli/build/libs/modloader.jar load example/build/libs/example-0.1.0.jar --pid <pid>` and read the server
console for the `[example]` lines; the mixin prints one every 100 ticks until `unload example`. Always pass `--pid`
on a shared machine: without it the CLI takes any JVM whose command line names minecraft, fabric or forge, another
session's game test included.

## Invariants

- The game loads `Agent` and `Bridge` once and keeps them; everything else under `lib/` is loaded again when the
  CLI's jar changes (`Agent.loaderVersion`). A change to either needs a game restart. `Agent`'s request (answer
  file, jar, command, one per line) and `Bridge`'s one method and one field have to stay as an older copy has them.
- Adapter classes load in every game, also one without their framework. Calls into the framework are fine in methods
  the loader only calls when it is present (`isLoaded`, `start`, `stop`). Code that passes one framework type as
  another (`FMLModContainer` as `ModContainer`) makes the verifier load both, so it lives in a class of its own
  (`RuntimeModContainer`).
- `ModFile.start` runs before any class of the mod loads: Forge turns the mod into a module of its own layer, which
  a loaded class forbids.
- `Framework.hasClass` instead of `Class.forName` for client classes: Forge throws a `RuntimeException` for them on
  a dedicated server.
- Mojang names for the game (`net.minecraft.client.Minecraft`, `net.minecraft.server.Main`) are used as strings;
  no module compiles against Minecraft.

Mixins:

- A redefined class keeps the fields, methods, modifiers and interfaces it was defined with, or the JVM refuses
  the whole batch. `Outliner` only swaps method bodies in the baseline; everything else goes to the companion.
- A constructor's code after its super call runs in the companion, where `java.lang.invoke` refuses to write the
  final fields of a record or a hidden class, and any final static: `Linker.trustedSetter` writes those through
  `jdk.internal.misc.Unsafe`.
- A class that has not run its static initializer when the compilation starts (`Linker.isInitialized`) gets the
  mixed initializer as a whole, moved like any changed method; only one that has run is cut into what ran and
  what the mixins append. A class that initializes while its mod still compiles keeps its own initializer, and
  nothing says so.
- A method reference in moved code goes through a stub whose receiver has the type the lambda captures, not
  the owner its handle names: javac names the superclass that declares the method, and `LambdaMetafactory`
  takes a captured argument of a static method only as the very type.
- A companion calls a method its mixins added as its own static copy, unless the call is virtual and another
  class adds the same name and descriptor (`Rewriter.Companion.overridden`): a mixin of a subclass may override
  it, as Fabric's `getAddon()` of the packet listeners and every handler of a mixin that extends another are.
  Those calls dispatch by the receiver in `Linker.Dispatch`, and the override's super call finds the copy of the
  class it names.
- Mixin numbers the `Args` classes of `@ModifyArgs` per run (`org.spongepowered.asm.synthetic.args.Args$1`), and
  the game's own Mixin has others by the same names. A compilation numbers its own from 1000001 (`MixinSession`),
  as the code the game's Mixin left in a NeoForge or Forge class still calls the game's. `GeneratedClassLoader`
  asks the compilation for that package before its parents and `Rewriter.isGame` never takes such a class for the
  game's. With shared names a companion got the wrong one either way (`NoSuchMethodError: Args$1.of(float,
  float)` on Fabric, `Args$2.of(int, int, double, long, float, int, Vec3, boolean)` on NeoForge).
- The game's mixin configs on a class a compilation changes run again, in the order the game's Mixin applied them
  (`MixinCompiler.GameConfig.order`) and with the compatibility level it gave them (`fabric-compat`): from 0.17.4 on
  a required config fails at a target the game lacks, where an older one only warns (no-telemetry's
  `YggdrasilUserApiService` on Quilt). `Outliner.rename` gives what they merge the loaded names back, MixinExtras'
  counters included (`mixinextras$bridge$...$142`, `...$mixinextras$wrapped$239`): a constructor's code before its
  super call has to come out as the game has it (CreativeCore and FancyMenu both wrap `new Matrix3x2fStack` in
  `GuiGraphicsExtractor`), and a method nobody changed stays out of the companion.
- A game config without Fabric's mod id decoration stays without one in a compilation: Mixin names merged handlers
  after the mod id, and Essential's `mixins.essential.json` as an id gave `Illegal method name
  "constant$fck000$mixins.essential.json$modify$3"`. `MixinSession.transform` gives the `@MixinMerged` of the
  game's own Mixin run our session id, so a mixin merging a method a game mixin merged follows Mixin's priority
  rules as at startup instead of failing (`Invalid @MixinMerged annotation`, NeoForge, which hands over no
  pre-mixin class).
- `MixinHost` hands Mixin every class the compilation's own jars lack as the game's framework hands it to the
  game's own Mixin (`Framework.preMixinClass`, access wideners applied), and only without one the class file or
  the game's copy. A `ClassInfo` from the plain class file kept `Minecraft.startUseItem` private after Fabric
  API's access widener opened it in the input (`Could not locate method metadata ... generating LVT`).
- A compilation starts MixinSquared (`MixinSquaredBootstrap.init`) when the game has it: the game's mixins use its
  dynamic selectors (`@MixinSquared:Handler`) and cancellers, which a plugin of some config registered at the
  game's start, and that config need not be among the compilation's (AsyncParticles on Quilt).
- `CompilerClassLoader` defines a game class that extends a Mixin type, such as a config plugin, again from its
  class file. With it go the classes of the game's own class loader (Minecraft's and its mods') from the jar of
  such a class or from a package it took, and every one whose code names ASM, Mixin or MixinExtras types. A
  plugin's code reaches far into its jar, and any class it shares with the game across that line breaks it:
  `loader constraint violation` on conditional-mixin's `AnnotationCleaner.onPreApply`, MixinSquared's
  `ExtensionRegistrar.register`, Sodium's `MixinOption` or Caffeine Config's `CaffeineConfig`, Debugify's
  `BugFixDataCache$ResolvedBugFixData` out of reach, CustomSkinLoader's `Class.forName("...MixinInfo")` finding the
  game's class, and ModernFix exits the game when its platform hooks come from the other side. The cost is state
  the mod set up at startup: MagicLib's plugin finds its `PlatformManager` empty in the copy, and that load is
  refused. The framework's classes, such as `FabricLauncherBase`, stay the game's. Services of Mixin and
  MixinExtras come only from its own jars, any other also from the game's: a plugin looks its mod's services up
  through the thread's class loader (Sodium Extra's `CaffeineConfigPlatform`).
- HotSpot before 25.0.4 (JDK-8376185; the runtime Mojang ships is 25.0.1) misreads the type annotations of record
  components when a redefinition renumbers the constant pool. The class then no longer finds its own fields
  (`NoSuchFieldError`), and every later redefinition of it is refused for a changed `Record` attribute.
  `Outliner.workAroundJdk8376185` takes those annotations out of every redefined class. `LoadTest` only fails
  for it on such a JDK: `JAVA_HOME=<jdk 25.0.1> ./gradlew :agent:test --rerun
  -Porg.gradle.java.installations.auto-detect=false -Porg.gradle.java.installations.paths=<jdk 25.0.1>`; without
  `--rerun` Gradle keeps the result of the other JDK.
- Every compilation runs Mixin over the class as the framework handed it to the game's own Mixin
  (`Framework.preMixinClass`, else `MixinManager.baselines`), with the configs that Mixin applied to it, and
  outlines against `baselines`, the bytes captured before the first redefinition. Never capture a class that is
  already redefined.
- `MixinManager.compile` is slow and runs on the attach thread; `apply` (the redefinition) and the mod's start run
  in one task on the client thread while the singleplayer server waits (`ModLoader.runOnGameThread`). A mod's
  handlers running before its start crashed LiquidBounce in a world. A dedicated server keeps ticking meanwhile.
- The game's Mixin named merged handlers, unique methods and fields after counters of its own run;
  `Outliner.rename` gives ours the loaded names, or every class the framework mixed would look changed.
- `Rewriter` writes a call kind (named after the instruction it replaces) and `Linker` links it: change them
  together. `Rewriter` judges from class files and may be wrong about a class changed while loading; `Linker` looks
  at the loaded class and decides.
- The `mixin` module never loads a game class; `ClassFiles` reads class files instead. The game's class files are
  read through a class loader that also reaches the mods; `Compiler.game` leaves out what the mods' jars have.
- Replacements keep the stack effect of the instruction they replace, so frames stay valid and nothing is
  recomputed, except the static initializer part (`Outliner.recompute`). A companion cannot name a non-public
  class: `Rewriter.erase` puts its public superclass in its place everywhere, frames included, and links what
  needs the class itself (casts, constructors, members declared with it).
- A mixin config plugin runs in the `CompilerClassLoader` on a copy of the mod's classes, before any of its mixins
  exist. One that already uses what the mod's mixins add fails there. A plugin of a mod the game loaded is
  defined there again from the game's class file.
- Mod classes load through `ModClassLoader`. On Fabric, Knot's parent is a `SharedClassLoader` that finds them,
  so libraries Knot loaded see the mods' classes and services. A mod's own lookups skip it (`MOD_LOOKUP`): the JVM
  keeps every class a loader returned, so a class Knot handed out once stays the old one after `unload`. Only
  `Mod.start` and the compilation run with the mod's as context class loader.
- Not done yet, each fails where it is used rather than at load: default methods of an interface a mixin adds;
  non-public game classes named as a type in a mod's own classes (only companions erase them).

## Agent usage

- Every commit made with an AI model adds its own file to `.agent-usage/`, recorded from the session logs of that
  commit: `agent-usage record <session log> --from <first call of this change> --change "<commit subject>"`, then
  `agent-usage check`. Never edit or delete an existing file.
- Every class, interface, enum or record a model wrote carries `@agent <model>` as the last tag of its doc comment,
  naming the model that writes it. No other doc: `/** @agent claude-opus-5-5 */` on its own.
- A model that later changes a method's logic adds its own tag; several models get one tag each, oldest first.
  Renames, imports, formatting and moves add none. Tags move with the code.
- Model ids as the provider names them, without provider prefix: `claude-opus-5-5`, `gpt-5.6-sol`.
- Code people wrote with AI only assisting gets no tag. Code taken from elsewhere keeps the tags it had there.
