# How ModLoader works

What happens in the game when mods are loaded, which parts of a mod run in which game, and where the limits are.
How to use it is in the [README](../README.md).

## Loading mods

```mermaid
flowchart LR
    cli["launcher, or<br/>modloader load a.jar b.jar"] -- attach --> agent
    subgraph game ["running Minecraft"]
        agent --> loader
        loader -- "is the id loaded?" --> framework["Fabric, Quilt, NeoForge, Forge<br/>or none"]
        loader -- "own class loader" --> mod["a.jar, b.jar"]
        framework -- entrypoints --> mod
        loader -- "mixin configs" --> mixin["own Mixin"]
        mixin -- "redefine" --> classes["loaded game classes"]
        classes -- "moved methods" --> companions["companion classes"]
        companions --> mod
    end
```

One `load` takes one jar or many and treats them as the mods of one game start:

1. The system properties given with it are set before anything of the mods runs: what `-D` is at launch.
2. Every jar is read. A jar that is a mod of no framework refuses the whole load. A jar whose mod id the game's
   framework, an earlier `load` or an earlier jar of this load already has is not loaded, and `load` says so.
3. The mods bundled in the jars join them, unless the game already has them. Dependencies are checked across all of
   them, so the jars may depend on each other, in any order.
4. The mixins of all of them are compiled together and applied in one step on the game thread. There, in the order
   the jars were given, every mod runs its framework entrypoints, and after the last of them every mod runs its
   `RuntimeMod`: it finds the other mods of the load started.
5. A failure anywhere takes the whole load out of the game again.

Mods bundled in a jar unload together with it.

### A game in a sandbox

A game under Flatpak or in a container has another process id, its own `/tmp` and other files than the launcher.
The crate reaches it through `/proc/<pid>/root`, as the JDK's own tools do, and copies the agent and every jar the
game does not have into `.modloader/attach` in the game's directory. A folder a property names is not copied:

```rust
if loader.can_see("/data/mymod") {
    // the game has this folder too
}
```

## What runs

| In the mod | Runs in a game with |
|---|---|
| Mixins, and code compiled against an access widener or transformer | any framework or none |
| `RuntimeMod` from the API | any framework or none |
| Fabric `main`, `client` and `server` entrypoints, and the others for the mods that ask for them; the mod is in Fabric Loader's mod list, and libraries of the game find its classes and services | Fabric, and any other but Quilt once ModLoader brought Fabric in |
| The same for a Fabric mod; the mod is in Quilt Loader's mod list, which the Fabric API on Quilt shows too. A `quilt.mod.json` is not read | Quilt |
| NeoForge `@Mod` classes, `@EventBusSubscriber` classes, mod bus lifecycle events | NeoForge |
| Forge `@Mod` class, `@EventBusSubscriber` classes, mod bus lifecycle events | Forge |

A mod built for a framework the game lacks runs its mixins and its `RuntimeMod`; with neither it is refused.

## Fabric in a game without it

A Fabric mod that depends on Fabric Loader, Fabric API or Fabric Language Kotlin brings them into a game without
Fabric (Quilt runs Fabric mods itself). The first such `load` downloads the newest stable Fabric Loader with its
libraries from Fabric's servers, and Fabric API and Fabric Language Kotlin for the game's Minecraft version from
Modrinth, checks each against the SHA-512 its server gives, and keeps them in `.modloader/<version>/` of the game
directory. Later loads take them from there without asking the network; delete the directory to get newer ones.
Fabric Loader joins the game's class path and runs without Knot. Of Fabric API only the modules come in that the
mods of the load name in their `depends` or whose classes their jars name, in their code or in a string, with what
they depend on. They and Fabric Language Kotlin load before the mods like any other mod, and stay with Fabric Loader
until the game exits. What Fabric API only does at the game's start, such as registering its content or the events
of the start, does not happen.

A jar of Fabric Loader, Fabric API, one of its modules or Fabric Language Kotlin that is given to a `load` in such a
game is left out, and `load` says so: all of Fabric API would come in with it, and Fabric brings what fits the game
itself. In a game Fabric started, these jars load like any mod.

## Mixins

The JVM lets a loaded class change what its methods do and nothing else: no new method, field or interface. So
ModLoader runs a Mixin of its own over the class as the game defined it, keeps the class's shape, and moves the rest
into a class beside it: handler methods, added methods and the new bodies of changed methods. The loaded class only
calls over. Added fields live outside the object, and code that reads a non-public member through an access widener
reaches it the same way. Mixins the game's own Mixin applied to the same class, such as Fabric API's, run again
together with the mod's, as they would have at startup. Unloading the mod puts the classes back as they were.

### What a running game cannot take from a mod

1. Anything tied to the game's start: registry content such as blocks and items, and what a mod only sets up in an
   event of the start, such as its commands when the server starts.
2. Assets and data: the jar is not added to the resource packs.
3. A field a mixin adds gets its initializer on the first read on objects that existed before, when the initializer
   reads nothing but the object; otherwise it starts at zero or null there.
4. A mixin that overrides a method its target only inherits, changes a constructor before the `super(...)` call, or
   appends code to a static initializer that sets a final field: the mod is refused with the reason. A change inside
   a static initializer has nothing left to change; it is left out and `load` says so.
5. An interface a mixin adds is seen by the code of the loaded mods, not by the game: the game's own `instanceof`
   stays false. In the same way an access widener cannot make a class extendable.
6. A mod that loaded native code, or a mod bundled with it, cannot be unloaded: the JVM keeps the library bound to
   the mod's class loader until the game exits and loads it into no other.

### Mods outside their framework

A mod outside the framework that started the game, such as a Fabric mod in a NeoForge game, meets classes that
framework never prepared for it. An injection into a part of the game NeoForge replaced, such as the hotbar it draws
as a layer, goes to the replacement. An injection that still finds no target or does not fit it, and a change the
list above refuses, are left out and `load` names them. Inside its own framework a mod applies in full or not at all.

## Unload

`unload` takes the mod's mixins out of the game and closes its class loader, so a rebuilt jar loads again without
restarting the game. A mod that was started through framework entrypoints and has no `RuntimeMod` stays until the
game exits: what it registered there cannot be taken back.
Native code keeps a mod in the game as well, as the last item of
[the list above](#what-a-running-game-cannot-take-from-a-mod) says.
