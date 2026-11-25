# ModLoader

Loads mods into a Minecraft that is already running, whether it runs Fabric, Quilt, NeoForge, Forge or no mod
framework at all, and applies their mixins to the classes the game has long loaded. A launcher does it through the
Rust library; `modloader` is the same as a command line tool.

Minecraft 26.3 and later, Java 25.

## Use

Take `modloader.jar` from the [snapshot release](https://github.com/CCBlueX/ModLoader/releases/tag/snapshot). It is
the agent that goes into the game, with the loader inside. The Rust crate in [rust](rust) puts it there: a library
for launchers, and a command line tool built from it. Neither starts a Java of its own: the game's JVM runs the
agent.

### In a launcher

```toml
modloader = { git = "https://github.com/CCBlueX/ModLoader" }
```

```rust
use modloader::ModLoader;

let loader = ModLoader::new(game.id(), "modloader.jar");
let loaded = loader.load(Path::new("mods/mymod.jar"))?;   // id, framework, is_new, notes
let mods = loader.mods()?;                                // what was loaded this way
loader.unload(&loaded.id)?;
```

`game` is the process the launcher started; `Jvm::all()` lists the running Java processes for a game it did not
start. Every call blocks until the game has answered.

### On the command line

Take the `modloader-…` program for your system from the same release and keep it in one folder with
`modloader.jar`. Run it, here renamed to `modloader`, as the user the game runs as (`chmod +x` it first on Linux and
macOS):

```sh
./modloader load mymod.jar another.jar
./modloader list
./modloader unload mymod
```

The game is found by its command line. `--pid <pid>` names it when several run, or when it was started as a plain
`server.jar`.

A mod loads once: when the game's framework or an earlier `load` already has its id, `load` says so and changes
nothing.

[How it works](docs/how-it-works.md) has the rest:

1. [What runs](docs/how-it-works.md#what-runs): mixins and a `RuntimeMod` in every game, framework entrypoints in a
   game with the mod's framework.
2. [Fabric in a game without it](docs/how-it-works.md#fabric-in-a-game-without-it): a Fabric mod brings Fabric
   along, which the first such `load` downloads.
3. [What a running game cannot take from a mod](docs/how-it-works.md#what-a-running-game-cannot-take-from-a-mod):
   registry content, assets and data, and some mixins.
4. [Unload](docs/how-it-works.md#unload): which mods can be taken out of the game again.

## API

A mod that implements `RuntimeMod` runs in every game and can be taken out again:

```java
public final class MyMod implements RuntimeMod {

    @Override
    public void load(LoadedMod mod) {
        // register listeners; keep `mod` and call mod.unload() to leave the game
    }

    @Override
    public void unload() {
        // remove them again
    }
}
```

List the class in `META-INF/services/net.ccbluex.modloader.api.RuntimeMod` and compile against the API as
`compileOnly`; the loader brings the classes. [example](example) has an entrypoint for every framework and a mixin.

```kotlin
repositories {
    maven("https://maven.ccbluex.net/snapshots")
}

dependencies {
    compileOnly("net.ccbluex:modloader-api:0.1.0-SNAPSHOT")
}
```

`unload` takes the mod's mixins out of the game and closes its class loader, so a rebuilt jar loads again without
restarting the game. A mod that was started through framework entrypoints and has no `RuntimeMod` stays until the
game exits.

## Build

With JDK 25 and Rust installed:

```sh
./gradlew build   # agent/build/dist: modloader and modloader.jar
```

## Agent usage

Most of this code was written by AI models. Each declaration they wrote names its model in an `@agent` doc tag.
These are all models used so far and what they cost, summed up by [agent-usage](https://github.com/1zun4/agent-usage):

<a href="https://ccbluex.github.io/ModLoader/ai-usage/">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://ccbluex.github.io/ModLoader/ai-usage-dark.svg">
    <img alt="AI models used in ModLoader and their cost" src="https://ccbluex.github.io/ModLoader/ai-usage-light.svg">
  </picture>
</a>

## Commits

`type(scope): subject`, lowercase, no trailing period.

`feat` `fix` `refactor` `chore` `docs` `test`

## License

This project is subject to the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.en.html) (GPLv3). This only applies to source code located directly in this clean repository. During the development and compilation process, additional source code may be used to which we have obtained no rights. Such code is not covered by the GPL license.

For those who are unfamiliar with the license, here is a summary of its main points. This is by no means legal advice nor legally binding.

*Actions that you are allowed to do:*

* Use
* Share
* Modify

*If you do decide to use ANY code from the source:*

* **You must disclose the source code of your modified work and the source code you took from this project. This means you are not allowed to use code from this project (even partially) in a closed-source (or even obfuscated) application.**
* **Your modified application must also be licensed under the GPL.**
