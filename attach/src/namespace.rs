//! Linux namespaces: a game under Flatpak or in a container has another process id, its own `/tmp` and other files
//! than this process. Elsewhere, and for a game beside this process, nothing here differs from the process itself.

use std::path::{self, Path, PathBuf};
use std::{env, fs, io};

use same_file::is_same_file;
use tempfile::{Builder, NamedTempFile, TempPath};

/// @agent claude-fable-5-2
pub struct Namespace {
    host: u32,
}

impl Namespace {
    pub fn of(pid: u32) -> Self {
        Self { host: pid }
    }

    /// The process id here, which signals go to.
    pub fn host(&self) -> u32 {
        self.host
    }

    /// The process id the JVM has for itself, which names its socket.
    #[cfg(unix)]
    pub fn pid(&self) -> u32 {
        fs::read_to_string(format!("/proc/{}/status", self.host))
            .ok()
            .and_then(|status| {
                let ids = status.lines().find_map(|line| line.strip_prefix("NSpid:"))?;
                ids.split_whitespace().last()?.parse().ok()
            })
            .unwrap_or(self.host)
    }

    /// The game's `/tmp` as this process reaches it.
    #[cfg(unix)]
    pub fn tmp(&self) -> Option<PathBuf> {
        self.root().map(|root| root.join("tmp")).filter(|tmp| tmp.is_dir())
    }

    /// Whether the game has the existing `path` as this process does.
    pub fn sees(&self, path: &Path) -> bool {
        let Some(root) = self.root() else { return true };
        // Without its links: one on the way would lead back out of the game's file system.
        let Ok(path) = fs::canonicalize(path) else { return false };
        let Ok(inside) = path.strip_prefix("/") else {
            return false;
        };
        is_same_file(root.join(inside), &path).unwrap_or(false)
    }

    /// The path the game opens `file` by: its own, or that of a copy where the game does not see it.
    pub fn share(&self, file: &Path) -> io::Result<PathBuf> {
        let file = path::absolute(file)?;
        if self.sees(&file) {
            return Ok(file);
        }
        let name = file
            .file_name()
            .ok_or_else(|| io::Error::other(format!("{} is no file", file.display())))?;
        // shortcut: files of the same name overwrite each other here; number them when one load has two.
        let (ours, theirs) = self.shared()?;
        let (copy, original) = (ours.join(name), fs::metadata(&file)?);
        let is_current = fs::metadata(&copy)
            .is_ok_and(|copy| copy.len() == original.len() && copy.modified().ok() >= original.modified().ok());
        if !is_current {
            // Written beside the copy and renamed over it: a jar the game has open stays whole.
            let staged = NamedTempFile::new_in(&ours)?;
            fs::copy(&file, staged.path())?;
            staged.persist(&copy).map_err(|error| error.error)?;
        }
        Ok(theirs.join(name))
    }

    /// An empty file for the game's answer: the path this process reads and the path the game writes.
    pub fn exchange(&self) -> io::Result<(TempPath, PathBuf)> {
        let temp = env::temp_dir();
        let (ours, theirs) = if self.sees(&temp) {
            (temp.clone(), temp)
        } else {
            self.shared()?
        };
        let answer = Builder::new().prefix("modloader-").tempfile_in(ours)?.into_temp_path();
        let inside = theirs.join(answer.file_name().unwrap_or_default());
        Ok((answer, inside))
    }

    /// `/proc/<pid>/root`, the file system as the game has it. A game of another user keeps it closed, and then its
    /// files are taken for this process's.
    fn root(&self) -> Option<PathBuf> {
        let root = PathBuf::from(format!("/proc/{}/root", self.host));
        fs::metadata(&root).is_ok().then_some(root)
    }

    /// A directory in the game's working directory, as this process and as the game reach it.
    fn shared(&self) -> io::Result<(PathBuf, PathBuf)> {
        let cwd = PathBuf::from(format!("/proc/{}/cwd", self.host));
        let ours = cwd.join(".modloader").join("attach");
        fs::create_dir_all(&ours)?;
        Ok((ours, fs::read_link(&cwd)?.join(".modloader").join("attach")))
    }
}

/// @agent claude-fable-5-2
#[cfg(test)]
mod tests {
    use std::{io, process};

    use tempfile::NamedTempFile;

    use super::Namespace;

    #[test]
    fn leaves_a_process_beside_this_one_alone() -> io::Result<()> {
        let own = Namespace::of(process::id());
        let file = NamedTempFile::new()?;

        #[cfg(unix)]
        assert_eq!(process::id(), own.pid());
        assert!(own.sees(file.path()));
        assert_eq!(file.path(), own.share(file.path())?);
        let (answer, answer_in_game) = own.exchange()?;
        assert_eq!(answer.to_path_buf(), answer_in_game);
        Ok(())
    }
}
