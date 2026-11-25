//! Loads mods into a running Minecraft, for launchers and other tools.
//!
//! [`ModLoader`] talks to the loader inside an instance and [`Jvm::all`] finds instances this process did not start.
//! No Java is started: the game's own JVM runs the agent in `modloader.jar`. Every call blocks until the game has
//! answered.
//!
//! ```no_run
//! # let process = std::process::Command::new("java").spawn()?;
//! let loader = modloader::ModLoader::new(process.id(), "modloader.jar");
//! let instance = loader.instance()?;
//! println!("Minecraft {} in {}", instance.version, instance.directory.display());
//! // One load: the mods start together, as at the game's start, with the property set before any of them runs.
//! let loaded = loader.load(&["mods/library.jar", "mods/mymod.jar"], &[("mymod.folder", "/data/mymod")])?;
//! for loaded in &loaded.mods {
//!     println!("{} runs on {}", loaded.id, loaded.framework);
//! }
//! loader.unload("mymod")?;
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```

mod attach;

use std::collections::HashMap;
use std::path::{self, Path, PathBuf};
use std::{fmt, fs, io};

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sysinfo::{ProcessRefreshKind, ProcessesToUpdate, System, UpdateKind};

/// The crate's version; an agent jar fits when its major.minor is the same.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Why a request did not go through.
///
/// @agent claude-fable-5-2
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// The game's JVM could not be reached or did not run the agent.
    #[error(transparent)]
    Attach(#[from] io::Error),
    /// The loader in the game said no. Its log has the stack trace.
    #[error("{0}")]
    Refused(String),
}

/// The loader inside one running instance, on Java 24 or later.
///
/// @agent claude-fable-5-2
#[derive(Debug, Clone)]
pub struct ModLoader {
    pid: u32,
    agent: PathBuf,
}

/// The instance a [`ModLoader`] talks to.
///
/// @agent claude-fable-5-2
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Instance {
    /// Minecraft's own version, such as `26.3`.
    pub version: String,
    /// The game directory; of a dedicated server, its working directory.
    pub directory: PathBuf,
    /// `Fabric`, `Quilt`, `NeoForge` or `Forge`, also when ModLoader brought it in. Empty in a game without any.
    pub frameworks: Vec<String>,
    pub is_client: bool,
}

/// What [`ModLoader::load`] did with the jars it was given.
///
/// @agent claude-fable-5-2
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Loaded {
    /// The jars that were loaded. Mods bundled in them and mods a framework brought in are not listed.
    pub mods: Vec<Mod>,
    /// The jars that were not needed: the game, an earlier load or an earlier jar of this load had the mod.
    pub present: Vec<Mod>,
    /// What the user should know: jars and mixins left out, a framework brought in.
    pub notes: Vec<String>,
}

/// A mod and the jar it came from.
///
/// @agent claude-fable-5-2
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Mod {
    pub id: String,
    /// `Fabric`, `Quilt`, `NeoForge` or `Forge`.
    pub framework: String,
    pub path: PathBuf,
}

impl ModLoader {
    /// `agent` is `modloader.jar`, which the game's JVM loads on the first request and keeps.
    pub fn new(pid: u32, agent: impl Into<PathBuf>) -> Self {
        Self {
            pid,
            agent: agent.into(),
        }
    }

    pub fn instance(&self) -> Result<Instance, Error> {
        self.request(json!({ "command": "instance" }))
    }

    /// Loads the jars together, as the mods of one game start, and sets `properties` as system properties of the
    /// game's JVM before anything of them runs. A jar that is no mod refuses the whole load.
    pub fn load<P: AsRef<Path>>(&self, jars: &[P], properties: &[(&str, &str)]) -> Result<Loaded, Error> {
        // The game opens the jars, from its own working directory.
        let items = jars
            .iter()
            .map(|jar| path::absolute(jar).and_then(|jar| utf8(&jar).map(str::to_owned)))
            .collect::<io::Result<Vec<String>>>()?;
        let properties: HashMap<&str, &str> = properties.iter().copied().collect();
        self.request(json!({ "command": "load", "items": items, "properties": properties }))
    }

    /// Unloads a mod loaded through ModLoader and returns what the user should know about it.
    pub fn unload(&self, id: &str) -> Result<Vec<String>, Error> {
        #[derive(Deserialize)]
        struct Unloaded {
            notes: Vec<String>,
        }
        let unloaded: Unloaded = self.request(json!({ "command": "unload", "item": id }))?;
        Ok(unloaded.notes)
    }

    /// The mods loaded through ModLoader, not those the game started with.
    pub fn mods(&self) -> Result<Vec<Mod>, Error> {
        #[derive(Deserialize)]
        struct LoadedMods {
            mods: Vec<Mod>,
        }
        let loaded: LoadedMods = self.request(json!({ "command": "list" }))?;
        Ok(loaded.mods)
    }

    fn request<T: DeserializeOwned>(&self, request: Value) -> Result<T, Error> {
        let answer = tempfile::Builder::new()
            .prefix("modloader-")
            .tempfile()?
            .into_temp_path();
        let agent = utf8(&self.agent)?;
        // Read by Agent.agentmain: where the answer goes, its own jar, then the loader's request.
        let options = format!("{}\n{agent}\n{request}", utf8(&answer)?);
        attach::load_agent(self.pid, agent, &options)?;
        parse(&fs::read_to_string(&answer)?)
    }
}

/// The loader answers JSON, with `error` when it refuses. An agent that could not start it answers plain text.
///
/// @agent claude-fable-5-2
fn parse<T: DeserializeOwned>(answer: &str) -> Result<T, Error> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Answer<T> {
        Refusal { error: String },
        Done(T),
    }
    match serde_json::from_str(answer) {
        Ok(Answer::Done(done)) => Ok(done),
        Ok(Answer::Refusal { error }) => Err(Error::Refused(error)),
        Err(_) => Err(Error::Refused(answer.to_string())),
    }
}

/// @agent claude-fable-5-2
fn utf8(path: &Path) -> io::Result<&str> {
    path.to_str()
        .ok_or_else(|| io::Error::other(format!("{} is not UTF-8, which the JVM takes paths in", path.display())))
}

/// A running Java process this user can see.
///
/// @agent claude-fable-5-2
#[derive(Debug, Clone)]
pub struct Jvm {
    pub pid: u32,
    pub command: Vec<String>,
}

impl Jvm {
    pub fn all() -> Vec<Jvm> {
        let mut system = System::new();
        system.refresh_processes_specifics(
            ProcessesToUpdate::All,
            true,
            ProcessRefreshKind::nothing().with_cmd(UpdateKind::Always),
        );
        system
            .processes()
            .values()
            // Linux lists every thread as a process of its own.
            .filter(|process| process.thread_kind().is_none())
            .filter(|process| {
                process
                    .name()
                    .to_string_lossy()
                    .to_ascii_lowercase()
                    .starts_with("java")
            })
            .map(|process| Jvm {
                pid: process.pid().as_u32(),
                command: process
                    .cmd()
                    .iter()
                    .map(|argument| argument.to_string_lossy().into_owned())
                    .collect(),
            })
            .collect()
    }

    /// Whether the command line names Minecraft or a mod framework, as the main classes and jars of vanilla, Fabric,
    /// Quilt, NeoForge and Forge launches do.
    pub fn is_minecraft(&self) -> bool {
        let command = self.command.join(" ").to_ascii_lowercase();
        ["minecraft", "fabric", "quilt", "forge"]
            .iter()
            .any(|name| command.contains(name))
    }

    /// The value after `name` on the command line, such as `--gameDir` or `--version`.
    pub fn argument(&self, name: &str) -> Option<&str> {
        let at = self.command.iter().position(|argument| argument == name)?;
        self.command.get(at + 1).map(String::as_str)
    }
}

/// The process id and the command line without its class paths, which run over many lines.
impl fmt::Display for Jvm {
    fn fmt(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
        write!(formatter, "{} ", self.pid)?;
        for argument in self.command.iter().filter(|argument| argument.len() <= 100) {
            write!(formatter, " {argument}")?;
        }
        Ok(())
    }
}

/// @agent claude-fable-5-2
#[cfg(test)]
mod tests {
    use std::path::Path;

    use super::{Error, Instance, Jvm, Loaded, parse};

    fn jvm(command: &[&str]) -> Jvm {
        Jvm {
            pid: 1,
            command: command.iter().map(|argument| argument.to_string()).collect(),
        }
    }

    #[test]
    fn tells_refusals_from_answers() {
        let loaded: Loaded = parse(
            r#"{"mods":[{"id":"a","framework":"Fabric","path":"/mods/a.jar"}],"present":[],"notes":["left out: b"]}"#,
        )
        .unwrap();
        assert_eq!("a", loaded.mods[0].id);
        assert_eq!(Path::new("/mods/a.jar"), loaded.mods[0].path);
        assert!(loaded.present.is_empty());
        assert_eq!(vec!["left out: b"], loaded.notes);

        let refused = |answer| match parse::<Loaded>(answer) {
            Err(Error::Refused(message)) => message,
            other => panic!("{other:?}"),
        };
        assert_eq!("not a mod", refused(r#"{"error":"not a mod"}"#));
        assert_eq!(
            "java.io.IOException: disk full",
            refused("java.io.IOException: disk full")
        );
    }

    #[test]
    fn reads_the_instance() {
        let instance: Instance =
            parse(r#"{"version":"26.3","directory":"/home/me/.minecraft","frameworks":["Fabric"],"isClient":true}"#)
                .unwrap();
        assert_eq!("26.3", instance.version);
        assert_eq!(Path::new("/home/me/.minecraft"), instance.directory);
        assert_eq!(vec!["Fabric"], instance.frameworks);
        assert!(instance.is_client);
    }

    #[test]
    fn finds_the_value_after_an_argument() {
        let game = jvm(&[
            "java",
            "net.minecraft.client.main.Main",
            "--gameDir",
            "/home/me/.minecraft",
            "--demo",
        ]);
        assert_eq!(Some("/home/me/.minecraft"), game.argument("--gameDir"));
        assert_eq!(None, game.argument("--demo"));
        assert_eq!(None, game.argument("--version"));
    }

    #[test]
    fn tells_games_from_other_java() {
        assert!(
            jvm(&[
                "java",
                "-cp",
                "/libs/fabric-loader.jar",
                "net.fabricmc.loader.impl.launch.knot.KnotClient"
            ])
            .is_minecraft()
        );
        assert!(jvm(&["javaw.exe", "net.neoforged.fml.startup.Client"]).is_minecraft());
        assert!(jvm(&["java", "-jar", "server.jar", "--gameDir", "C:\\Users\\me\\.minecraft"]).is_minecraft());
        assert!(!jvm(&["java", "-jar", "gradle-wrapper.jar", "build"]).is_minecraft());
    }

    #[test]
    fn leaves_class_paths_out_of_the_summary() {
        let class_path = "/libs/a.jar:".repeat(20);
        assert_eq!(
            "1  java -cp Main",
            jvm(&["java", "-cp", &class_path, "Main"]).to_string()
        );
    }
}
