//! Loads mods into a running Minecraft, for launchers and other tools.
//!
//! [`ModLoader`] talks to the loader inside a game and [`Jvm::all`] finds games this process did not start. No Java
//! is started: the game's own JVM runs the agent in `modloader.jar`. Every call blocks until the game has answered.
//!
//! ```no_run
//! # let game = std::process::Command::new("java").spawn()?;
//! let loader = modloader::ModLoader::new(game.id(), "modloader.jar");
//! let loaded = loader.load("mods/mymod.jar".as_ref())?;
//! println!("{} runs on {}", loaded.id, loaded.framework);
//! loader.unload(&loaded.id)?;
//! # Ok::<(), Box<dyn std::error::Error>>(())
//! ```

mod attach;

use std::path::{self, Path, PathBuf};
use std::{fmt, fs, io};

use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::json;
use sysinfo::{ProcessRefreshKind, ProcessesToUpdate, System, UpdateKind};

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

/// The loader inside one running game, on Java 24 or later.
///
/// @agent claude-fable-5-2
#[derive(Debug, Clone)]
pub struct ModLoader {
    pid: u32,
    agent: PathBuf,
}

/// What [`ModLoader::load`] did.
///
/// @agent claude-fable-5-2
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Loaded {
    pub id: String,
    /// `Fabric`, `Quilt`, `NeoForge` or `Forge`.
    pub framework: String,
    /// False when the game or an earlier load already had the mod.
    pub is_new: bool,
    /// What the user should know: mixins left out, a framework brought in.
    pub notes: Vec<String>,
}

/// A mod loaded through ModLoader.
///
/// @agent claude-fable-5-2
#[derive(Debug, Clone, Deserialize)]
pub struct Mod {
    pub id: String,
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

    pub fn load(&self, jar: &Path) -> Result<Loaded, Error> {
        // The game opens the jar, from its own working directory.
        self.request("load", Some(utf8(&path::absolute(jar)?)?))
    }

    /// Unloads a mod loaded through ModLoader and returns what the user should know about it.
    pub fn unload(&self, id: &str) -> Result<Vec<String>, Error> {
        #[derive(Deserialize)]
        struct Unloaded {
            notes: Vec<String>,
        }
        Ok(self.request::<Unloaded>("unload", Some(id))?.notes)
    }

    /// The mods loaded through ModLoader, not those the game started with.
    pub fn mods(&self) -> Result<Vec<Mod>, Error> {
        #[derive(Deserialize)]
        struct LoadedMods {
            mods: Vec<Mod>,
        }
        Ok(self.request::<LoadedMods>("list", None)?.mods)
    }

    fn request<T: DeserializeOwned>(&self, command: &str, item: Option<&str>) -> Result<T, Error> {
        let answer = tempfile::Builder::new()
            .prefix("modloader-")
            .tempfile()?
            .into_temp_path();
        let agent = utf8(&self.agent)?;
        // Read by Agent.agentmain: where the answer goes, its own jar, then the loader's request.
        let options = format!(
            "{}\n{agent}\n{}",
            utf8(&answer)?,
            json!({ "command": command, "item": item })
        );
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
    use super::{Error, Jvm, Loaded, parse};

    fn jvm(command: &[&str]) -> Jvm {
        Jvm {
            pid: 1,
            command: command.iter().map(|argument| argument.to_string()).collect(),
        }
    }

    #[test]
    fn tells_refusals_from_answers() {
        let loaded: Loaded =
            parse(r#"{"id":"example","framework":"Fabric","isNew":true,"notes":["left out: a"]}"#).unwrap();
        assert!(loaded.is_new);
        assert_eq!(vec!["left out: a"], loaded.notes);

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
