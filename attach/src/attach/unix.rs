//! Linux and macOS: the JVM listens on a Unix socket in its temporary directory. It opens the socket when it gets
//! SIGQUIT while a file there names its process id.

use std::fs::{self, File};
use std::io;
use std::os::unix::fs::MetadataExt;
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::thread;
use std::time::{Duration, Instant};

/// @agent claude-fable-5-2
pub fn connect(pid: u32) -> io::Result<UnixStream> {
    let directory = temp_directory();
    let socket = directory.join(format!(".java_pid{pid}"));
    if !socket.exists() {
        check_handles_quit(pid)?;
        let trigger = directory.join(format!(".attach_pid{pid}"));
        File::create(&trigger)?;
        let opened = if unsafe { libc::kill(pid as libc::pid_t, libc::SIGQUIT) } == 0 {
            // The JDK's own tools give the JVM this long.
            let deadline = Instant::now() + Duration::from_secs(10);
            while !socket.exists() && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(100));
            }
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        };
        let _ = fs::remove_file(&trigger);
        opened?;
        if !socket.exists() {
            return Err(io::Error::other(format!(
                "process {pid} did not open {}; it is no JVM, or one started with -XX:+DisableAttachMechanism",
                socket.display()
            )));
        }
    }
    // The directory is shared, so the name alone proves nothing.
    if fs::metadata(&socket)?.uid() != unsafe { libc::geteuid() } {
        return Err(io::Error::other(format!(
            "{} belongs to another user",
            socket.display()
        )));
    }
    UnixStream::connect(&socket)
}

/// SIGQUIT ends a process that does not handle it. A JVM does, unless it runs with -Xrs.
///
/// @agent claude-fable-5-2
#[cfg(target_os = "linux")]
fn check_handles_quit(pid: u32) -> io::Result<()> {
    let status =
        fs::read_to_string(format!("/proc/{pid}/status")).map_err(|_| io::Error::other(format!("no process {pid}")))?;
    let caught = status
        .lines()
        .find_map(|line| line.strip_prefix("SigCgt:"))
        .and_then(|mask| u64::from_str_radix(mask.trim(), 16).ok())
        .unwrap_or(0);
    if caught & (1 << (libc::SIGQUIT - 1)) == 0 {
        return Err(io::Error::other(format!(
            "process {pid} is no JVM that takes attach requests"
        )));
    }
    Ok(())
}

/// @agent claude-fable-5-2
#[cfg(not(target_os = "linux"))]
fn check_handles_quit(_pid: u32) -> io::Result<()> {
    Ok(())
}

/// HotSpot's choice on macOS is the user's temporary directory, whatever TMPDIR says.
///
/// @agent claude-fable-5-2
#[cfg(target_os = "macos")]
fn temp_directory() -> PathBuf {
    use std::ffi::OsString;
    use std::os::unix::ffi::OsStringExt;

    let mut path = vec![0u8; libc::PATH_MAX as usize];
    let length = unsafe { libc::confstr(libc::_CS_DARWIN_USER_TEMP_DIR, path.as_mut_ptr().cast(), path.len()) };
    if length == 0 || length > path.len() {
        return PathBuf::from("/tmp");
    }
    // The length counts the terminating zero.
    path.truncate(length - 1);
    PathBuf::from(OsString::from_vec(path))
}

/// @agent claude-fable-5-2
#[cfg(not(target_os = "macos"))]
fn temp_directory() -> PathBuf {
    PathBuf::from("/tmp")
}
