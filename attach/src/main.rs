use std::env;
use std::error::Error;
use std::path::PathBuf;
use std::process::ExitCode;

use clap::{Parser, Subcommand};
use modloader::{Jvm, ModLoader};

// The help text comes from `about` and the variants; the type docs are for readers of the code.
/// @agent claude-fable-5-2
#[derive(Parser)]
#[command(version, about = "Loads mods into a running Minecraft", long_about = None)]
struct Arguments {
    /// Process id of the game; without it the one running Minecraft is used
    #[arg(long, global = true)]
    pid: Option<u32>,
    #[command(subcommand)]
    command: Command,
}

/// @agent claude-fable-5-2
#[derive(Subcommand)]
enum Command {
    /// Load mods into the running game, together
    Load {
        /// System property to set in the game before the mods start; may repeat
        #[arg(short = 'D', value_name = "KEY=VALUE", value_parser = property)]
        properties: Vec<(String, String)>,
        #[arg(required = true)]
        jars: Vec<PathBuf>,
    },
    /// Unload mods loaded this way
    Unload {
        #[arg(required = true)]
        ids: Vec<String>,
    },
    /// Show the mods loaded this way
    List,
    /// Show the game's version, mod frameworks and directory
    Game,
}

/// @agent claude-fable-5-2
fn property(argument: &str) -> Result<(String, String), String> {
    let (key, value) = argument.split_once('=').ok_or("expected KEY=VALUE")?;
    Ok((key.to_string(), value.to_string()))
}

/// @agent claude-fable-5-2
fn main() -> ExitCode {
    match run(Arguments::parse()) {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::FAILURE,
        Err(error) => {
            eprintln!("error: {error}");
            ExitCode::FAILURE
        }
    }
}

/// Whether every unload went through; one that did not has its reason printed. Any other failure is the error.
///
/// @agent claude-fable-5-2
fn run(arguments: Arguments) -> Result<bool, Box<dyn Error>> {
    let agent = env::current_exe()?.with_file_name("modloader.jar");
    if !agent.is_file() {
        return Err(format!(
            "{} is missing; modloader.jar belongs next to this program",
            agent.display()
        )
        .into());
    }
    let pid = match arguments.pid {
        Some(pid) => pid,
        None => game()?,
    };
    let loader = ModLoader::new(pid, agent);
    let mut is_done = true;
    match arguments.command {
        Command::Load { properties, jars } => {
            if let Some(jar) = jars.iter().find(|jar| !jar.is_file()) {
                return Err(format!("{}: no such file", jar.display()).into());
            }
            let properties: Vec<(&str, &str)> = properties
                .iter()
                .map(|(key, value)| (key.as_str(), value.as_str()))
                .collect();
            let loaded = loader.load(&jars, &properties)?;
            for present in &loaded.present {
                println!("{} is already loaded", present.id);
            }
            for loaded in &loaded.mods {
                println!("loaded {} ({})", loaded.id, loaded.framework);
            }
            loaded.notes.iter().for_each(|note| println!("  {note}"));
        }
        Command::Unload { ids } => {
            for id in ids {
                match loader.unload(&id) {
                    Ok(notes) => {
                        println!("unloaded {id}");
                        notes.iter().for_each(|note| println!("  {note}"));
                    }
                    Err(error) => {
                        println!("error: {id}: {error}");
                        is_done = false;
                    }
                }
            }
        }
        Command::List => {
            let mods = loader.mods()?;
            if mods.is_empty() {
                println!("no mods loaded");
            }
            for loaded in mods {
                println!("{} ({}) {}", loaded.id, loaded.framework, loaded.path.display());
            }
        }
        Command::Game => {
            let running = loader.game()?;
            println!("{}", running.version);
            if running.frameworks.is_empty() {
                println!("no mod framework");
            } else {
                println!("{}", running.frameworks.join(", "));
            }
            println!("{}", running.directory.display());
        }
    }
    Ok(is_done)
}

/// The one running Minecraft, or the choice to make with `--pid`.
///
/// @agent claude-fable-5-2
fn game() -> Result<u32, String> {
    let running = Jvm::all();
    let games: Vec<&Jvm> = running.iter().filter(|jvm| jvm.is_minecraft()).collect();
    if let [game] = games[..] {
        return Ok(game.pid);
    }
    let (mut message, choice) = if games.is_empty() {
        (String::from("no running Minecraft found"), running.iter().collect())
    } else {
        (String::from("several games are running"), games)
    };
    message.push_str("; pass --pid with one of:");
    for jvm in choice {
        message.push_str(&format!("\n  {jvm}"));
    }
    Err(message)
}
