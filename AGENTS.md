# ModLoader

Minecraft 26.3+ (Mojang names, unobfuscated), Java 25, no runtime dependencies. Root package `net.ccbluex.modloader`.

| Module | Runs in | Holds |
|---|---|---|
| `cli/` | the CLI's JVM; `agent.Agent` in the game's system class loader | commands and attach; builds `modloader.jar` with the modules below under `lib/runtime` |
| `loader/` | a class loader below the game's | `ModLoader` (the commands), `Mod`, `ModClassLoader`, the `Framework` and `ModFile` contract |
| `api/` | same | what mods compile against |
| `framework/<name>/` | same | one `Framework` and `ModFile` per mod framework, found through `META-INF/services` |
| `example/` | a mod class loader | a mod with an entrypoint for every framework; the tests load it |

A new framework is a module under `framework/`, its name in `settings.gradle.kts` and its `runtime(...)` line in
`cli/build.gradle.kts`.

## Commands

```sh
flock /tmp/modloader-gradle.lock ./gradlew build   # compiles, runs the tests; they load mods into a stand-in game JVM
```

Against real games: install 26.3 dedicated servers (vanilla `server.jar`, Fabric server launcher, NeoForge and Forge
installers with `--installServer`), start each with `-Xmx` and `eula=true`, then
`java -jar cli/build/libs/modloader.jar load example/build/libs/example-0.1.0.jar --pid <pid>` and read the server
console for the `[example]` lines. Always pass `--pid` on a shared machine: without it the CLI takes any JVM whose
command line names minecraft, fabric or forge, another session's game test included.

## Invariants

- The game loads `Agent` once and keeps it; everything under `lib/` is loaded again when the CLI's jar changes
  (`Agent.loaderVersion`). A change to `Agent` needs a game restart, and its request (answer file, jar, command, one
  per line) has to stay readable by an older `Agent`.
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
