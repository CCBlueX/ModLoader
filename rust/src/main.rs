use std::error::Error;
use std::path::PathBuf;
use std::process::ExitCode;
use std::{env, fmt};

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
    /// Load mods into the running game
    Load {
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

/// Whether every item went through. One that did not has its reason printed.
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
    let mut report = |item: &dyn fmt::Display, result: Result<(String, Vec<String>), Box<dyn Error>>| match result {
        Ok((line, notes)) => {
            println!("{line}");
            notes.iter().for_each(|note| println!("  {note}"));
        }
        Err(error) => {
            println!("error: {item}: {error}");
            is_done = false;
        }
    };
    match arguments.command {
        Command::Load { jars } => {
            for jar in jars {
                let loaded = if jar.is_file() {
                    loader.load(&jar).map_err(Into::into)
                } else {
                    Err("no such file".into())
                };
                report(
                    &jar.display(),
                    loaded.map(|loaded| match loaded.is_new {
                        true => (format!("loaded {} ({})", loaded.id, loaded.framework), loaded.notes),
                        false => (format!("{} is already loaded", loaded.id), loaded.notes),
                    }),
                );
            }
        }
        Command::Unload { ids } => {
            for id in ids {
                let unloaded = loader.unload(&id).map_err(Into::into);
                report(&id, unloaded.map(|notes| (format!("unloaded {id}"), notes)));
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
