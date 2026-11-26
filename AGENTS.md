# ModLoader

Minecraft 26.3+ (Mojang names, unobfuscated), Java 25. Root package `net.ccbluex.modloader`.

`game/` is what goes into the game, `attach/` what reaches it from outside, `compat/` the mods that make existing
client builds work when loaded this way. Gradle project names are the module names (`:loader`, `:fabric`,
`:liquidbounce`); `settings.gradle.kts` maps them to their folders.

| Module | Runs in | Holds |
|---|---|---|
| `attach/` | a launcher's process, or its own as the command line tool (crate `modloader`; the program needs feature `cli`) | `ModLoader` (the typed requests), finding games (`Jvm`), HotSpot's attach protocol (`attach.rs`, `attach/unix.rs`, `attach/windows.rs`), a game's Linux namespaces (`namespace.rs`); `main.rs` is the tool |
| `game/agent/` | the game's system class loader | `Agent`, the entry of `modloader.jar`, built here with the modules below under `lib/`; the tests of the whole |
| `game/bridge/` | the game's bootstrap class path | `Bridge.link`, the bootstrap method of every call the compiler writes into a class |
| `game/loader/` | a class loader below the game's | `ModLoader` (the commands), `Mod`, `ModClassLoader`, `Instance` (what the instance says about itself), the `Framework` and `ModFile` contract; in `mixin`: `MixinManager` (redefines and reverts), `Linker` (links the Bridge calls), the `MixinCompiler` contract |
| `api/` | same | what mods compile against |
| `game/framework/<name>/` | same | one `Framework` and `ModFile` per mod framework, found through `META-INF/services` |
| `game/mixin/` | a `CompilerClassLoader`, new for every compilation, with the bundled Mixin, MixinExtras and ASM | `Compiler`: `MixinSession` runs Mixin, `Outliner` splits a mixed class into redefinition and companion, `Rewriter` hands instructions to `Linker` |
| `example/` | a mod class loader | a mod with an entrypoint for every framework and a mixin; the tests load it |
| `compat/liquidbounce/` | a mod class loader, as the first jar of a launcher's load | the Fabric mod `modloader-liquidbounce`, which lets unchanged LiquidBounce builds run when loaded this way |

A new framework is a module under `game/framework/`, its name in `settings.gradle.kts` and its `runtime(...)` line in
`game/agent/build.gradle.kts`. A new compat mod is a module under `compat/` and its name in `settings.gradle.kts`.

The version is one number in two places that change together: `modloaderVersion` (the default in the root
`build.gradle.kts`, with `-SNAPSHOT`) and `version` in `attach/Cargo.toml` (without). A tag `v0.1.0` makes CI build
with `-PmodloaderVersion=0.1.0` and publish to Maven's releases; `main` publishes the default to snapshots. A module
that publishes applies `maven-publish` and declares one publication `maven` with its `artifactId`; the repository
and the POM come from the root script. `agent` publishes its jar alone, without the `java` component, so the POM of
`net.ccbluex:modloader` lists no dependencies.

`README.md` is usage only. Which parts of a mod run where, how mixins reach loaded classes and what a running game
cannot take are in `docs/how-it-works.md`; a changed behavior or limit is written there.

## Commands

```sh
flock /tmp/modloader-gradle.lock ./gradlew build   # Java and, through cargo, attach/; runs the Java tests
(cd attach && cargo test --all-features && cargo clippy --all-features --all-targets)
```

`agent`'s tests run the built program with the jar beside it (`game/agent/build/dist`) against a stand-in game:
`game/agent/src/test/java/net/minecraft` is that game, `game/agent/src/mod` the mod with mixins they load,
`game/agent/src/widened` what that mod compiles against in place of an access widener. CI runs them on Linux, macOS
and Windows, the only place the code of the other two systems runs. Here it only compiles:
`cargo clippy --all-features --target x86_64-pc-windows-gnu` and `--target aarch64-apple-darwin`.

Against real games: install 26.3 dedicated servers (vanilla `server.jar`, Fabric server launcher, NeoForge and Forge
installers with `--installServer`), start each with `-Xmx` and `eula=true`, then
`game/agent/build/dist/modloader load example/build/libs/example-0.1.0-SNAPSHOT.jar --pid <pid>` and read the server
console for the `[example]` lines; the mixin prints one every 100 ticks until `unload example`. Always pass `--pid`
on a shared machine: without it the program takes any Java process whose command line names minecraft, fabric,
quilt or forge, another session's game test included.

## Invariants

- The game loads `Agent` and `Bridge` once and keeps them; everything else under `lib/` is loaded again when
  `modloader.jar` changes (`Agent.loaderVersion`). A change to either needs a game restart. `Agent`'s request
  (answer file, jar, the loader's request, one per line; written by `ModLoader::request` in `attach/src/lib.rs`) and
  `Bridge`'s one method and one field have to stay as an older copy has them.
- The loader's request and answers are JSON: the records at the top of the Java `ModLoader` and the serde types in
  `attach/src/lib.rs` change together. `Agent` has no Gson; when the loader cannot start, it answers plain text, which
  the crate reports as `Error::Refused`. A launcher pairs its crate with the newest agent on Maven of the same
  major.minor, so within one a request keeps its fields and their meaning and an answer only gains fields; anything
  else is a new minor in both places.
- One `load` is one request with all its jars, and what `ModLoader.load` does with them is in this order: system
  properties, reading every jar (one that is no mod refuses all), leaving out what the game has (`present`) and
  what a framework that did not start the game supplies itself (`Framework.bringsIn`), opening the rest,
  `Framework.bringIn` once per such framework, dependencies, one mixin compilation, one task on the game thread.
  That task runs `Mod.startInFramework` of every mod, then `Mod.start` (the `RuntimeMod`s) of every mod, in the
  order given. `Mod.start` is where a class of a mod first loads, after every `ModFile.start`. A load or unload
  whose mods have no mixin configs compiles nothing: each compilation's companions stay with what the game still
  holds of them, and `modloader-liquidbounce` goes and comes with every detach and attach.
- A jar `Framework.bringsIn` names has to stay out of `Framework.bringIn`: a Fabric API jar among the mods names
  every module, and `FabricApi.used` would bring all of them. `FabricApi.used` reads the jars it is given and none
  inside them; bundled mods are in the list themselves. A mod the framework brings wins over one of the same id in
  the load (`ModLoader.bringIn`).
- Formats and plumbing go through libraries, not own code: NightConfig for `mods.toml` (shipped in `lib/runtime`;
  the loader's class loader asks the game first, so NeoForge's and Forge's own copy wins there), the game's Gson and
  Guava (`compileOnly`), and in the crate serde, clap, tempfile, thiserror and sysinfo.
- Nullness is JSpecify: every package has a `package-info.java` with `@NullMarked`, and NullAway, the one Error
  Prone check that runs, fails the build of main code on a violation. A new package without that file is skipped
  without a word. `jspecify` is `compileOnly`: the game ships it, and the JVM ignores an annotation whose class is
  missing, which keeps `Agent` and `Bridge` loadable anywhere.
- The crate speaks attach protocol 2 only, which Java 24 brought: the request has no size limit, and on Windows it
  travels over the pipe, so the thread started in the JVM calls `JVM_EnqueueOperation_v2` directly, at jvm.dll's
  address there plus the function's offset in a mapping of the same file here. Protocol 1 would need machine code
  written into the JVM.
- On Linux `check_handles_quit` reads the process's caught signals first: SIGQUIT ends a process that is no JVM.
- A game under Flatpak or in a container is in other namespaces. `Namespace` takes the JVM's own process id
  (`NSpid`) for the socket in the game's `/tmp` (`/proc/<pid>/root/tmp`), and `share` copies what the game does not
  have at the same path into `.modloader/attach` under its working directory. The Gradle tests do not cover it:
  run the game in one container and the tool in another started with `--pid=host`.
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
- `Fabric.bringIn` puts Fabric Loader and the libraries the game lacks on the system class path for good, and
  `RunningInstance` is its launcher and game provider. Until then nothing in the loader's class loader may link a
  `net.fabricmc` class: a failed lookup sticks to the class that tried, so only `Framework.hasClass` asks.
  `Fabric.isBroughtIn` compares class names, as a newer loader has its own `RunningInstance`. A later load whose mods
  need more of Fabric API gets those modules on top.
- NeoForge and Forge run the game's payload channels themselves, `minecraft:register` included. In their games
  Fabric API's networking module comes without its mixins (`FabricMod.mixinConfigs`), or its own registration
  payload meets their codec and every connection drops in the configuration phase (`RegistrationPayload cannot
  be cast to MinecraftRegisterPayload`). Its API classes stay; a call that needs a connection's addon, such as
  `ClientPlayNetworking.getSendable`, fails there with a `ClassCastException`.
- A mod whose framework did not start the game (`Framework.hasStartedGame`) is relaxed: a failing entrypoint
  (`Mod.startInFramework`), an injector without target (`MixinHost.relax`, `MixinSession.leftOut`) or that does not
  fit it (`MixinHost.drop`, then Mixin runs over the class again), a method change `Outliner` refuses and a failing
  appended static initializer (`MixinManager.apply`) of a class it mixes into become `left out:` notes. An accessor
  whose method the target has already is dropped, and `Linker` sends calls of it to the target's method. An injector
  that names a method or call the game's framework replaced (`Framework.replacements`: NeoForge's and Forge's HUD
  layers and screen hooks) gets its replacement, where the class has the method that took its place or no longer
  calls the original. A framework lists only what it replaced in the running game: Forge keeps
  `Hud.extractHotbarAndDecorations` but draws its parts as layers (`ForgeLayeredDraw`), so the HUD of a Fabric mod
  hooked there never showed. An injector that names a method by name alone, which the framework overloaded, gets
  the overloads its `@Inject` handler's parameters and its points' targets fit (`MixinHost.pin`): Mixin takes the
  first by that name, on Forge often the framework's (`EntityFluidInteraction.applyCurrentTo`,
  `Player.getDestroySpeed`, `ModelBlockRenderer.tesselateBlock`). Everything else stays strict.
- Quilt Loader ships Fabric Loader's classes (`FabricLoaderImpl`, `FabricLauncherBase`, Knot) as shims over its
  own: `Fabric.isPresent` is false on Quilt. The Quilt adapter reads and starts Fabric mods through the Fabric
  adapter; only the mod list and entrypoints are Quilt's own. Quilt names its mods' mixin configs
  `#<mod id>:<resource>`; `MixinHost` reads them without the prefix.
- `Fabric.isPresent` asks for Knot, not for Fabric Loader's API: a Fabric layer on Forge or NeoForge (Fabricated,
  Sinytra Connector) ships that API without Knot and owns its packages in the game's module layer, where a Fabric
  Loader brought in would meet its classes. `Fabric.bringIn` refuses there.

Mixins:

- A redefined class keeps the fields, methods, modifiers and interfaces it was defined with, or the JVM refuses
  the whole batch. `Outliner` only swaps method bodies in the baseline; everything else goes to the companion.
- A constructor's code after its super call runs in the companion, where `java.lang.invoke` refuses to write the
  final fields of a record or a hidden class, and any final static: `Linker.trustedSetter` writes those through
  `jdk.internal.misc.Unsafe`.
- A class that has not run its static initializer when the compilation starts (`Linker.isInitialized`) gets the
  mixed initializer as a whole, moved like any changed method; only one that has run is cut into what ran and
  what the mixins append. On a title screen that never connected this is every packet class, which is where
  Fabric API registers its payload types: without them a game Fabric was brought into drops every connection in
  the configuration phase (`RegistrationPayload cannot be cast to DiscardedPayload`). A class that initializes
  while its mod still compiles keeps its own initializer, and nothing says so.
- A method reference in moved code goes through a stub whose receiver has the type the lambda captures, not
  the owner its handle names: javac names the superclass that declares the method, and `LambdaMetafactory`
  takes a captured argument of a static method only as the very type.
- A lambda in moved code whose functional interface the companion cannot name, a package-private one such as
  the line consumer of `ChatComponent.extractRenderState`, links through `Linker` (`lambda`) to a
  `java.lang.reflect.Proxy` of that interface over the implementation: `LambdaMetafactory` takes neither the
  erased type nor an interface of another package (`LambdaConversionException: java.lang.Object is not an
  interface` in a world).
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
- A class the JVM hands back on retransformation has its methods in an order of its own; `Compiler.inFileOrder`
  restores the class file's order before Mixin, whose selectors take the first method they match.
- Every compilation runs Mixin over the class as the framework handed it to the game's own Mixin
  (`Framework.preMixinClass`, else `MixinManager.baselines`), with the configs that Mixin applied to it, and
  outlines against `baselines`, the bytes captured before the first redefinition. Never capture a class that is
  already redefined.
- `MixinManager.compile` is slow and runs on the attach thread; `apply` (the redefinition) and the mod's start run
  in one task on the client thread while the singleplayer server waits (`ModLoader.runOnGameThread`). A mod's
  handlers running before its start crashed LiquidBounce in a world. A dedicated server keeps ticking meanwhile.
- On a client outside a world `Loading` puts the game's `LoadingOverlay` up from the start of a load until the
  task on the client thread begins, unless another overlay is up. It is gone before the mods start, so a mod that
  starts over several ticks shows its own, as `LateStart` of `compat/liquidbounce` does. A resource reload asked
  for while a `LoadingOverlay` is up waits until it is gone, and LiquidBounce starts its splash over for every
  new overlay: `LateStart` takes its own down, starts the reload, and puts its own back in place of the reload's,
  which it then follows. In a world neither shows an overlay: the player would be blind while the game goes on.
  `Loading` refuses a load while the game is still starting (no `Minecraft.gui` yet), before anything is loaded.
- `LateStart` turns LiquidBounce's automatic GUI scale into 2 on the option's value itself: the client's mixin
  does it through `OptionInstance.set`, whose validation asks `Window.calculateScale` once the game runs, and
  Legacy4J's hook there reads the option again (`StackOverflowError`).
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
  needs the class itself (casts, constructors, members declared with it). A mod's class gets the same for
  non-public game classes, which an access widener made public only where Knot loads the game; its own fields and
  methods are erased too, so every class of the mod agrees, and a mod method overriding a game method declared
  with such a class stops overriding it.
- A mixin config plugin runs in the `CompilerClassLoader` on a copy of the mod's classes, before any of its mixins
  exist. One that already uses what the mod's mixins add fails there. A plugin of a mod the game loaded is
  defined there again from the game's class file.
- Mod classes load through `ModClassLoader`. On Fabric and Quilt, Knot's parent is a `SharedClassLoader` that finds
  them, so libraries Knot loaded see the mods' classes and services. A mod's own lookups skip it (`MOD_LOOKUP`): the
  JVM keeps every class a loader returned, so a class Knot handed out once stays the old one after `unload`. Only
  `Mod.startInFramework`, `Mod.start` and the compilation run with the mod's as context class loader.
- A mod's class loader takes `kotlin` and `kotlinx` classes from the loaded mods' jars before the game's
  (`ModClassLoader.isModFirst`), and `Rewriter` counts those as the mods', not the game's: a game whose Kotlin
  comes from another framework's language mod (KotlinLangForge on NeoForge) keeps kotlinx's services to its own
  loader, and LiquidBounce found no `Dispatchers.Main` (`Check failed` at its start). Counted as the game's, a
  call inside the mods' Kotlin linked to the game's copy (`AbstractMethodError ... NoReceiver.access$000`).
- Not done yet, fails where it is used rather than at load: default methods of an interface a mixin adds.

## Agent usage

- Every commit made with an AI model adds its own file to `.agent-usage/`, recorded from the session logs of that
  commit: `agent-usage record <session log> --from <first call of this change> --change "<commit subject>"`, then
  `agent-usage check`. Never edit or delete an existing file.
- Every class, interface, enum or record a model wrote carries `@agent <model>` as the last tag of its doc comment,
  naming the model that writes it. No other doc: `/** @agent claude-opus-5-5 */` on its own. In Rust it is the last
  line of the `///` doc of every struct, enum, trait and top-level function, and of a test module for its tests.
- A model that later changes a method's logic adds its own tag; several models get one tag each, oldest first.
  Renames, imports, formatting and moves add none. Tags move with the code.
- Model ids as the provider names them, without provider prefix: `claude-opus-5-5`, `gpt-5.6-sol`.
- Code people wrote with AI only assisting gets no tag. Code taken from elsewhere keeps the tags it had there.
