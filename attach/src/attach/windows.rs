//! Windows: a thread started in the JVM's process calls its `JVM_EnqueueOperation_v2` with the name of a pipe, which
//! the JVM then opens for the request and the reply.

use std::ffi::{CString, c_void};
use std::fs::File;
use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
use std::time::{SystemTime, UNIX_EPOCH};
use std::{io, mem, process, ptr};

use windows_sys::Win32::Foundation::{
    ERROR_BAD_LENGTH, ERROR_PIPE_CONNECTED, FreeLibrary, GetLastError, HANDLE, INVALID_HANDLE_VALUE,
};
use windows_sys::Win32::Storage::FileSystem::{FILE_FLAG_FIRST_PIPE_INSTANCE, PIPE_ACCESS_DUPLEX};
use windows_sys::Win32::System::Diagnostics::Debug::WriteProcessMemory;
use windows_sys::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, MODULEENTRY32W, Module32FirstW, Module32NextW, TH32CS_SNAPMODULE,
};
use windows_sys::Win32::System::LibraryLoader::{DONT_RESOLVE_DLL_REFERENCES, GetProcAddress, LoadLibraryExW};
use windows_sys::Win32::System::Memory::{
    MEM_COMMIT, MEM_RELEASE, MEM_RESERVE, PAGE_READWRITE, VirtualAllocEx, VirtualFreeEx,
};
use windows_sys::Win32::System::Pipes::{
    ConnectNamedPipe, CreateNamedPipeA, PIPE_READMODE_BYTE, PIPE_REJECT_REMOTE_CLIENTS, PIPE_TYPE_BYTE, PIPE_WAIT,
};
use windows_sys::Win32::System::Threading::{
    CreateRemoteThread, GetExitCodeThread, INFINITE, OpenProcess, PROCESS_CREATE_THREAD, PROCESS_QUERY_INFORMATION,
    PROCESS_VM_OPERATION, PROCESS_VM_READ, PROCESS_VM_WRITE, WaitForSingleObject,
};

use crate::namespace::Namespace;

/// @agent claude-fable-5-2
pub fn connect(game: &Namespace) -> io::Result<File> {
    let pid = game.host();
    let access =
        PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION | PROCESS_VM_READ | PROCESS_VM_WRITE;
    let process = owned(unsafe { OpenProcess(access, 0, pid) })?;
    let enqueue = enqueue_address(pid)?;

    let unique = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |time| time.as_nanos());
    let name = CString::new(format!(r"\\.\pipe\javatool{}-{unique}", process::id())).expect("no zero in the name");
    // shortcut: default pipe security, so an elevated caller cannot reach a game that is not elevated; give the pipe
    // the security descriptor the JDK's attach uses when that comes up
    let pipe = owned(unsafe {
        CreateNamedPipeA(
            name.as_ptr().cast(),
            PIPE_ACCESS_DUPLEX | FILE_FLAG_FIRST_PIPE_INSTANCE,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
            1,
            4096,
            8192,
            0,
            ptr::null(),
        )
    })?;

    let bytes = name.as_bytes_with_nul();
    let status = unsafe {
        let process = process.as_raw_handle();
        let remote = VirtualAllocEx(
            process,
            ptr::null(),
            bytes.len(),
            MEM_COMMIT | MEM_RESERVE,
            PAGE_READWRITE,
        );
        if remote.is_null() {
            return Err(io::Error::last_os_error());
        }
        let status = call(process, enqueue, remote, bytes);
        // The JVM copied the name before the call returned.
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        status?
    };
    match status {
        0 => {}
        100 => {
            return Err(io::Error::other(format!(
                "process {pid} runs with -XX:+DisableAttachMechanism"
            )));
        }
        code => {
            return Err(io::Error::other(format!(
                "the JVM did not take the attach request: {code}"
            )));
        }
    }

    // The JVM may have opened the pipe already, which counts as connected.
    if unsafe { ConnectNamedPipe(pipe.as_raw_handle(), ptr::null_mut()) } == 0
        && unsafe { GetLastError() } != ERROR_PIPE_CONNECTED
    {
        return Err(io::Error::last_os_error());
    }
    Ok(File::from(pipe))
}

/// Runs `function(argument)` on a new thread of the process, with `bytes` at `argument`, and returns what it returns.
///
/// @agent claude-fable-5-2
unsafe fn call(process: HANDLE, function: usize, argument: *mut c_void, bytes: &[u8]) -> io::Result<u32> {
    unsafe {
        if WriteProcessMemory(process, argument, bytes.as_ptr().cast(), bytes.len(), ptr::null_mut()) == 0 {
            return Err(io::Error::last_os_error());
        }
        let function = mem::transmute::<usize, unsafe extern "system" fn(*mut c_void) -> u32>(function);
        let thread = owned(CreateRemoteThread(
            process,
            ptr::null(),
            0,
            Some(function),
            argument,
            0,
            ptr::null_mut(),
        ))?;
        WaitForSingleObject(thread.as_raw_handle(), INFINITE);
        let mut status = 0;
        if GetExitCodeThread(thread.as_raw_handle(), &mut status) == 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(status)
    }
}

/// Where `JVM_EnqueueOperation_v2` is in the process: where its jvm.dll sits, plus where the function sits in the
/// DLL, taken from a mapping of the same file here. No code has to be written into the JVM this way.
///
/// @agent claude-fable-5-2
fn enqueue_address(pid: u32) -> io::Result<usize> {
    let snapshot = loop {
        let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, pid) };
        // Windows asks for a retry while the process loads or unloads modules.
        if snapshot != INVALID_HANDLE_VALUE || unsafe { GetLastError() } != ERROR_BAD_LENGTH {
            break owned(snapshot)?;
        }
    };
    let mut module: MODULEENTRY32W = unsafe { mem::zeroed() };
    module.dwSize = size_of::<MODULEENTRY32W>() as u32;
    let mut found = unsafe { Module32FirstW(snapshot.as_raw_handle(), &mut module) } != 0;
    while found && !text(&module.szModule).eq_ignore_ascii_case("jvm.dll") {
        found = unsafe { Module32NextW(snapshot.as_raw_handle(), &mut module) } != 0;
    }
    if !found {
        return Err(io::Error::other(format!(
            "process {pid} has no jvm.dll, so it is no JVM"
        )));
    }

    // Mapped without running its code or loading what it depends on.
    let library = unsafe { LoadLibraryExW(module.szExePath.as_ptr(), ptr::null_mut(), DONT_RESOLVE_DLL_REFERENCES) };
    if library.is_null() {
        return Err(io::Error::last_os_error());
    }
    let function = unsafe { GetProcAddress(library, c"JVM_EnqueueOperation_v2".as_ptr().cast()) };
    let offset = function.map(|function| function as usize - library as usize);
    unsafe { FreeLibrary(library) };
    match offset {
        Some(offset) => Ok(module.modBaseAddr as usize + offset),
        None => Err(io::Error::other(
            "the game's Java is older than 24, the first to take this attach protocol",
        )),
    }
}

/// @agent claude-fable-5-2
fn owned(handle: HANDLE) -> io::Result<OwnedHandle> {
    if handle.is_null() || handle == INVALID_HANDLE_VALUE {
        Err(io::Error::last_os_error())
    } else {
        Ok(unsafe { OwnedHandle::from_raw_handle(handle) })
    }
}

/// @agent claude-fable-5-2
fn text(units: &[u16]) -> String {
    let length = units.iter().position(|&unit| unit == 0).unwrap_or(units.len());
    String::from_utf16_lossy(&units[..length])
}
