//! HotSpot's attach protocol, version 2, which Java 24 and later speak: no size limit on arguments, and on Windows
//! the request goes over the pipe as it does over the socket elsewhere.

#[cfg(unix)]
#[path = "attach/unix.rs"]
mod os;
#[cfg(windows)]
#[path = "attach/windows.rs"]
mod os;

use std::io::{self, Read, Write};

use crate::namespace::Namespace;

/// Loads a Java agent into the JVM, as `VirtualMachine.loadAgent` of the JDK does.
///
/// @agent claude-fable-5-2
pub fn load_agent(game: &Namespace, jar: &str, options: &str) -> io::Result<()> {
    let mut jvm = os::connect(game)?;
    jvm.write_all(&request("load", &["instrument", "false", &format!("{jar}={options}")]))?;
    let mut reply = Vec::new();
    jvm.read_to_end(&mut reply)?;
    result(&String::from_utf8_lossy(&reply))
}

/// `2\0<size>\0<command>\0(<argument>\0)*`, the size counting everything after it.
///
/// @agent claude-fable-5-2
fn request(command: &str, arguments: &[&str]) -> Vec<u8> {
    let mut data = Vec::new();
    for part in [command].iter().chain(arguments) {
        data.extend_from_slice(part.as_bytes());
        data.push(0);
    }
    let mut request = format!("2\0{}\0", data.len()).into_bytes();
    request.append(&mut data);
    request
}

/// The reply is the command's status on a line, then what the command printed: for `load`, `return code: ` and what
/// the agent library returned.
///
/// @agent claude-fable-5-2
fn result(reply: &str) -> io::Result<()> {
    let (status, output) = reply.split_once('\n').unwrap_or((reply, ""));
    let output = output.trim();
    Err(io::Error::other(match (status, output.strip_prefix("return code: ")) {
        ("0", Some("0")) => return Ok(()),
        ("", _) => "the JVM closed the connection without an answer".to_string(),
        // An older JVM answers a version it does not know with this status.
        ("101", _) => "the game's Java is older than 24, the first to take this attach protocol".to_string(),
        // Codes of the JDK's agent library for Java agents.
        ("0", Some("100")) => "the JVM cannot open the agent jar, or it names no Agent-Class".to_string(),
        ("0", Some("102")) => "the agent failed to start; the game's log has the reason".to_string(),
        _ => format!("the JVM did not load the agent: {status} {output}"),
    }))
}

/// @agent claude-fable-5-2
#[cfg(test)]
mod tests {
    use super::{request, result};

    #[test]
    fn sizes_the_request() {
        assert_eq!(
            b"2\x0018\0load\0instrument\0a\0".as_slice(),
            request("load", &["instrument", "a"])
        );
    }

    #[test]
    fn reads_the_agent_return_code() {
        assert!(result("0\nreturn code: 0\n").is_ok());
        assert!(
            result("0\nreturn code: 102\n")
                .unwrap_err()
                .to_string()
                .contains("failed to start")
        );
        assert!(
            result("101\nunknown version")
                .unwrap_err()
                .to_string()
                .contains("older than 24")
        );
        assert!(result("").is_err());
    }
}
