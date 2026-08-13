#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
const PROVIDER_ID: &str = "Obiente.NextcloudNative";
#[cfg(windows)]
const RECOVERABLE_ROOT_ARGUMENT: &str = "--recoverable-root";
#[cfg(windows)]
const MAX_RECOVERABLE_ROOTS: usize = 16;
#[cfg(any(windows, test))]
const ACCOUNT_ID_LENGTH: usize = 64;
#[cfg(any(windows, test))]
const MAX_SYNC_ROOT_IDENTITY_BYTES: usize = 4_096;
#[cfg(any(windows, test))]
const MAX_DISPLAY_NAME_CHARACTERS: usize = 128;

#[cfg(any(windows, test))]
fn is_windows_absence_hresult(value: i32) -> bool {
    matches!(value as u32, 0x8007_0002 | 0x8007_0003 | 0x8007_0490)
}

#[cfg(any(windows, test))]
fn paths_refer_to_same_existing_entry(
    first: &std::path::Path,
    second: &std::path::Path,
) -> std::io::Result<bool> {
    Ok(first.canonicalize()? == second.canonicalize()?)
}

#[cfg(any(windows, test))]
fn normalized_windows_path_units(value: &[u16]) -> Vec<u16> {
    const BACKSLASH: u16 = b'\\' as u16;
    const SLASH: u16 = b'/' as u16;
    const COLON: u16 = b':' as u16;
    const EXTENDED_PREFIX: &[u16] = &[BACKSLASH, BACKSLASH, b'?' as u16, BACKSLASH];
    const EXTENDED_UNC_PREFIX: &[u16] = &[
        BACKSLASH,
        BACKSLASH,
        b'?' as u16,
        BACKSLASH,
        b'U' as u16,
        b'N' as u16,
        b'C' as u16,
        BACKSLASH,
    ];

    let mut units: Vec<u16> = value
        .iter()
        .map(|unit| if *unit == SLASH { BACKSLASH } else { *unit })
        .collect();
    if units
        .get(..EXTENDED_UNC_PREFIX.len())
        .is_some_and(|prefix| {
            prefix
                .iter()
                .zip(EXTENDED_UNC_PREFIX)
                .all(|(actual, expected)| ascii_windows_path_unit_eq(*actual, *expected))
        })
    {
        units.splice(..EXTENDED_UNC_PREFIX.len(), [BACKSLASH, BACKSLASH]);
    } else if units.starts_with(EXTENDED_PREFIX) {
        units.drain(..EXTENDED_PREFIX.len());
    }
    while units.last() == Some(&BACKSLASH)
        && !(units.len() == 3 && units.get(1) == Some(&COLON))
        && units.len() > 1
    {
        units.pop();
    }
    units
}

#[cfg(any(windows, test))]
fn ascii_windows_path_unit_eq(first: u16, second: u16) -> bool {
    fn lowercase(unit: u16) -> u16 {
        if (b'A' as u16..=b'Z' as u16).contains(&unit) {
            unit + u16::from(b'a' - b'A')
        } else {
            unit
        }
    }
    lowercase(first) == lowercase(second)
}

#[cfg(any(windows, test))]
fn normalized_windows_paths_match<Compare>(
    first: &[u16],
    second: &[u16],
    compare_case_insensitive: Compare,
) -> bool
where
    Compare: FnOnce(&[u16], &[u16]) -> bool,
{
    let first = normalized_windows_path_units(first);
    let second = normalized_windows_path_units(second);
    !first.is_empty() && !second.is_empty() && compare_case_insensitive(&first, &second)
}

#[cfg(any(windows, test))]
#[derive(Debug, PartialEq, Eq)]
enum RegisteredPathState {
    Missing,
    SameExisting,
    DifferentExisting,
}

#[cfg(any(windows, test))]
fn registered_path_state(
    registered: &std::path::Path,
    requested: &std::path::Path,
) -> std::io::Result<RegisteredPathState> {
    if !registered.try_exists()? {
        return Ok(RegisteredPathState::Missing);
    }
    Ok(
        if paths_refer_to_same_existing_entry(registered, requested)? {
            RegisteredPathState::SameExisting
        } else {
            RegisteredPathState::DifferentExisting
        },
    )
}

#[cfg(any(windows, test))]
fn owned_registration_path_is_safe_to_unregister<Missing, SameEntry>(
    exact_registered_path: bool,
    registered_path_is_missing: Missing,
    registered_path_is_same_entry: SameEntry,
) -> std::io::Result<bool>
where
    Missing: FnOnce() -> std::io::Result<bool>,
    SameEntry: FnOnce() -> std::io::Result<bool>,
{
    if exact_registered_path {
        return Ok(true);
    }
    if registered_path_is_missing()? {
        return Ok(true);
    }
    registered_path_is_same_entry()
}

#[cfg(any(windows, test))]
#[derive(Debug, PartialEq, Eq)]
enum ExistingRegistrationAction {
    KeepCurrent,
    ReplaceCurrent,
    RemoveStaleOwned,
    RetainOwnedRecovery,
    ReportOwnedPathConflict,
    IgnoreForeign,
    RejectUnsafeCurrent,
}

#[cfg(any(windows, test))]
#[derive(Debug, PartialEq, Eq)]
enum OwnedCurrentRegistrationState {
    Ready,
    Replaceable,
    RequestedRootConflict,
    UnsafeExistingPath,
}

#[cfg(any(windows, test))]
fn existing_registration_action(
    current_id: bool,
    provider_owned: bool,
    current_state: OwnedCurrentRegistrationState,
) -> ExistingRegistrationAction {
    match (current_id, provider_owned, current_state) {
        (true, true, OwnedCurrentRegistrationState::Ready) => {
            ExistingRegistrationAction::KeepCurrent
        }
        (true, true, OwnedCurrentRegistrationState::Replaceable) => {
            ExistingRegistrationAction::ReplaceCurrent
        }
        (true, true, OwnedCurrentRegistrationState::RequestedRootConflict) => {
            ExistingRegistrationAction::RejectUnsafeCurrent
        }
        (true, true, OwnedCurrentRegistrationState::UnsafeExistingPath) => {
            ExistingRegistrationAction::RejectUnsafeCurrent
        }
        (true, false, _) => ExistingRegistrationAction::RejectUnsafeCurrent,
        (false, true, OwnedCurrentRegistrationState::Replaceable) => {
            ExistingRegistrationAction::RemoveStaleOwned
        }
        (false, true, OwnedCurrentRegistrationState::RequestedRootConflict) => {
            ExistingRegistrationAction::ReportOwnedPathConflict
        }
        (false, true, _) => ExistingRegistrationAction::RetainOwnedRecovery,
        (false, false, _) => ExistingRegistrationAction::IgnoreForeign,
    }
}

#[cfg(any(windows, test))]
fn account_id_from_sync_root_id(value: &str) -> Option<&str> {
    let (provider_and_sid, account_id) = value.rsplit_once('!')?;
    if provider_and_sid.starts_with("Obiente.NextcloudNative!") && valid_account_id(account_id) {
        Some(account_id)
    } else {
        None
    }
}

#[cfg(any(windows, test))]
fn recoverable_root_matches(
    recoverable_roots: &std::collections::HashMap<String, std::path::PathBuf>,
    account_id: &str,
    registered_path: &std::path::Path,
) -> bool {
    recoverable_roots
        .get(account_id)
        .is_some_and(|recovery_root| {
            matches!(
                registered_path_state(registered_path, recovery_root),
                Ok(RegisteredPathState::SameExisting)
            )
        })
}

#[cfg(windows)]
#[derive(Debug)]
struct OwnedPathConflict;

#[cfg(windows)]
impl std::fmt::Display for OwnedPathConflict {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("an owned Cloud Files registration already uses this path")
    }
}

#[cfg(windows)]
impl std::error::Error for OwnedPathConflict {}

#[cfg(windows)]
#[derive(Debug)]
struct RegistrationNotFound;

#[cfg(windows)]
impl std::fmt::Display for RegistrationNotFound {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("the Cloud Files registration was not found")
    }
}

#[cfg(windows)]
impl std::error::Error for RegistrationNotFound {}

#[cfg(windows)]
#[derive(Debug)]
struct UnsafeRegistrationConflict;

#[cfg(windows)]
impl std::fmt::Display for UnsafeRegistrationConflict {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("the Cloud Files registration cannot be changed safely")
    }
}

#[cfg(windows)]
impl std::error::Error for UnsafeRegistrationConflict {}

#[cfg(any(windows, test))]
fn valid_account_id(value: &str) -> bool {
    value.len() == ACCOUNT_ID_LENGTH
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(any(windows, test))]
fn valid_display_name(value: &str) -> bool {
    let count = value.encode_utf16().count();
    (1..=MAX_DISPLAY_NAME_CHARACTERS).contains(&count) && !value.chars().any(char::is_control)
}

#[cfg(any(windows, test))]
fn decode_identity_hex(value: &str) -> Result<Vec<u8>, &'static str> {
    if value.is_empty()
        || value.len() > MAX_SYNC_ROOT_IDENTITY_BYTES * 2
        || !value.len().is_multiple_of(2)
    {
        return Err("invalid identity length");
    }
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let high = hex_nibble(pair[0]).ok_or("invalid identity encoding")?;
            let low = hex_nibble(pair[1]).ok_or("invalid identity encoding")?;
            Ok((high << 4) | low)
        })
        .collect()
}

#[cfg(any(windows, test))]
fn hex_nibble(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        _ => None,
    }
}

#[cfg(test)]
fn lowercase_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut encoded = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        encoded.push(DIGITS[(byte >> 4) as usize] as char);
        encoded.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    encoded
}

#[cfg(any(windows, test))]
fn sid_string(bytes: &[u8]) -> Option<String> {
    if bytes.len() < 8 {
        return None;
    }
    let subauthority_count = bytes[1] as usize;
    if bytes.len() != 8 + subauthority_count * 4 {
        return None;
    }
    let authority = bytes[2..8]
        .iter()
        .fold(0u64, |value, byte| (value << 8) | u64::from(*byte));
    let mut sid = format!("S-{}-{authority}", bytes[0]);
    for subauthority in bytes[8..].chunks_exact(4) {
        let value = u32::from_le_bytes(subauthority.try_into().ok()?);
        sid.push('-');
        sid.push_str(&value.to_string());
    }
    Some(sid)
}

#[cfg(windows)]
mod platform {
    use super::{
        ExistingRegistrationAction, MAX_RECOVERABLE_ROOTS, OwnedCurrentRegistrationState,
        OwnedPathConflict, PROVIDER_ID, RECOVERABLE_ROOT_ARGUMENT, RegisteredPathState,
        RegistrationNotFound, UnsafeRegistrationConflict, account_id_from_sync_root_id,
        decode_identity_hex, existing_registration_action, is_windows_absence_hresult,
        normalized_windows_paths_match, owned_registration_path_is_safe_to_unregister,
        paths_refer_to_same_existing_entry, recoverable_root_matches, registered_path_state,
        sid_string, valid_account_id, valid_display_name,
    };
    use std::collections::HashMap;
    use std::ffi::{OsStr, OsString};
    use std::mem::size_of;
    use std::path::{Path, PathBuf};
    use windows::Security::Cryptography::CryptographicBuffer;
    use windows::Storage::Provider::{
        StorageProviderHardlinkPolicy, StorageProviderHydrationPolicy,
        StorageProviderHydrationPolicyModifier, StorageProviderInSyncPolicy,
        StorageProviderPopulationPolicy, StorageProviderSyncRootInfo,
        StorageProviderSyncRootManager,
    };
    use windows::Storage::StorageFolder;
    use windows::Win32::Foundation::{CloseHandle, E_FAIL, HANDLE};
    use windows::Win32::Globalization::{CSTR_EQUAL, CompareStringOrdinal};
    use windows::Win32::Security::{
        GetLengthSid, GetTokenInformation, TOKEN_QUERY, TOKEN_USER, TokenUser,
    };
    use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};
    use windows::Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize, RoUninitialize};
    use windows::core::{Error as WindowsError, GUID, HSTRING, Result as WindowsResult};

    fn registered_path_is_missing(path: &HSTRING) -> Result<bool, std::io::Error> {
        PathBuf::from(path.to_os_string())
            .try_exists()
            .map(|exists| !exists)
    }

    fn registered_path_matches(path: &HSTRING, requested: &Path) -> Result<bool, std::io::Error> {
        if registered_path_lexically_matches(path, requested) {
            return Ok(true);
        }
        paths_refer_to_same_existing_entry(&PathBuf::from(path.to_os_string()), requested)
    }

    fn registered_path_lexically_matches(path: &HSTRING, requested: &Path) -> bool {
        use std::os::windows::ffi::OsStrExt;

        let registered: Vec<u16> = path.to_os_string().encode_wide().collect();
        let requested: Vec<u16> = requested.as_os_str().encode_wide().collect();
        normalized_windows_paths_match(&registered, &requested, |first, second| {
            // SAFETY: both slices remain alive for the call and the API reads exactly their
            // declared lengths without requiring terminating nulls.
            unsafe { CompareStringOrdinal(first, second, true) == CSTR_EQUAL }
        })
    }

    fn current_registration_state(
        existing: &StorageProviderSyncRootInfo,
        root: &Path,
        display_name: &HSTRING,
        icon_resource: &HSTRING,
        context: &windows::Storage::Streams::IBuffer,
    ) -> WindowsResult<OwnedCurrentRegistrationState> {
        let existing_path = match existing.Path().and_then(|folder| folder.Path()) {
            Ok(path) => path,
            Err(failure) if is_windows_absence_hresult(failure.code().0) => {
                return Ok(OwnedCurrentRegistrationState::Replaceable);
            }
            Err(failure) => return Err(failure),
        };
        match registered_path_state(&PathBuf::from(existing_path.to_os_string()), root) {
            Ok(RegisteredPathState::Missing) => {
                return Ok(OwnedCurrentRegistrationState::Replaceable);
            }
            Ok(RegisteredPathState::SameExisting) => {}
            Ok(RegisteredPathState::DifferentExisting) | Err(_) => {
                return Ok(OwnedCurrentRegistrationState::UnsafeExistingPath);
            }
        }
        let metadata_matches = (|| -> WindowsResult<bool> {
            Ok(existing.DisplayNameResource()? == *display_name
                && existing.IconResource()? == *icon_resource
                && CryptographicBuffer::Compare(&existing.Context()?, context)?)
        })()
        .unwrap_or(false);
        Ok(if metadata_matches {
            OwnedCurrentRegistrationState::Ready
        } else {
            // Re-registering the same directory updates provider metadata without moving or
            // deleting local files.
            OwnedCurrentRegistrationState::Replaceable
        })
    }

    fn non_current_registration_state(
        existing: &StorageProviderSyncRootInfo,
        existing_id: &HSTRING,
        requested_root: &Path,
        recoverable_roots: &HashMap<String, PathBuf>,
    ) -> OwnedCurrentRegistrationState {
        let existing_path = match existing.Path().and_then(|folder| folder.Path()) {
            Ok(path) => path,
            Err(failure) if is_windows_absence_hresult(failure.code().0) => {
                return OwnedCurrentRegistrationState::Replaceable;
            }
            Err(_) => return OwnedCurrentRegistrationState::UnsafeExistingPath,
        };
        let registered_path = PathBuf::from(existing_path.to_os_string());
        match registered_path_state(&registered_path, &registered_path) {
            Ok(RegisteredPathState::Missing) => OwnedCurrentRegistrationState::Replaceable,
            Ok(RegisteredPathState::SameExisting) => {
                if matches!(
                    registered_path_state(&registered_path, requested_root),
                    Ok(RegisteredPathState::SameExisting)
                ) {
                    return OwnedCurrentRegistrationState::RequestedRootConflict;
                }
                let existing_id_value = existing_id.to_string();
                let Some(account_id) = account_id_from_sync_root_id(&existing_id_value) else {
                    return OwnedCurrentRegistrationState::UnsafeExistingPath;
                };
                if recoverable_root_matches(recoverable_roots, account_id, &registered_path) {
                    OwnedCurrentRegistrationState::Replaceable
                } else {
                    OwnedCurrentRegistrationState::UnsafeExistingPath
                }
            }
            Ok(RegisteredPathState::DifferentExisting) | Err(_) => {
                OwnedCurrentRegistrationState::UnsafeExistingPath
            }
        }
    }

    fn unregister_owned_registration(id: &HSTRING) -> windows::core::Result<()> {
        match StorageProviderSyncRootManager::Unregister(id) {
            Ok(()) => Ok(()),
            Err(failure) if is_windows_absence_hresult(failure.code().0) => Ok(()),
            Err(failure) => Err(failure),
        }
    }
    const PROVIDER_GUID: GUID = GUID::from_u128(0x6d456713_7d9a_4a39_90ce_127998de42d7);

    struct OwnedHandle(HANDLE);

    impl Drop for OwnedHandle {
        fn drop(&mut self) {
            // SAFETY: OpenProcessToken returned this owned handle and it is closed exactly once.
            unsafe {
                let _ = CloseHandle(self.0);
            }
        }
    }

    struct WinRtApartment;

    impl WinRtApartment {
        fn initialize() -> WindowsResult<Self> {
            // SAFETY: this process initializes and uninitializes WinRT on the same main thread.
            unsafe { RoInitialize(RO_INIT_MULTITHREADED)? };
            Ok(Self)
        }
    }

    impl Drop for WinRtApartment {
        fn drop(&mut self) {
            // SAFETY: paired with the successful RoInitialize call on this thread.
            unsafe { RoUninitialize() };
        }
    }

    fn registration_error(message: &'static str) -> WindowsError {
        WindowsError::new(E_FAIL, message)
    }

    fn current_user_sid() -> WindowsResult<String> {
        let mut raw_token = HANDLE::default();
        // SAFETY: raw_token is a valid out pointer and TOKEN_QUERY is the only requested access.
        unsafe {
            OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut raw_token)?;
        }
        let token = OwnedHandle(raw_token);
        let mut required_bytes = 0u32;
        // The first call intentionally has no output buffer and reports its required length.
        let _ = unsafe { GetTokenInformation(token.0, TokenUser, None, 0, &mut required_bytes) };
        if required_bytes < size_of::<TOKEN_USER>() as u32 {
            return Err(registration_error(
                "Windows did not return a valid user identity.",
            ));
        }
        let word_bytes = size_of::<usize>();
        let mut storage = vec![0usize; (required_bytes as usize).div_ceil(word_bytes)];
        // SAFETY: storage is aligned for TOKEN_USER and has at least required_bytes capacity.
        unsafe {
            GetTokenInformation(
                token.0,
                TokenUser,
                Some(storage.as_mut_ptr().cast()),
                required_bytes,
                &mut required_bytes,
            )?;
        }
        // SAFETY: GetTokenInformation populated storage with a TOKEN_USER structure.
        let token_user = unsafe { &*storage.as_ptr().cast::<TOKEN_USER>() };
        let sid_length = unsafe { GetLengthSid(token_user.User.Sid) } as usize;
        if sid_length == 0 {
            return Err(registration_error(
                "Windows did not return a valid user identity.",
            ));
        }
        // SAFETY: TOKEN_USER owns a valid SID for the lifetime of storage.
        let sid =
            unsafe { std::slice::from_raw_parts(token_user.User.Sid.0.cast::<u8>(), sid_length) };
        sid_string(sid).ok_or_else(|| registration_error("Windows returned an invalid user SID."))
    }

    fn sync_root_id(account_id: &str) -> WindowsResult<HSTRING> {
        let id = format!("{PROVIDER_ID}!{}!{account_id}", current_user_sid()?);
        if id.len() > 255 {
            return Err(registration_error(
                "The Windows sync root identity is too long.",
            ));
        }
        Ok(HSTRING::from(id))
    }

    fn validate_registration_paths(root: &Path, icon: &Path) -> Result<(), &'static str> {
        if !root.is_absolute() || !root.is_dir() {
            return Err("invalid sync root");
        }
        if !icon.is_absolute() || !icon.is_file() {
            return Err("invalid icon resource");
        }
        Ok(())
    }

    fn register(
        root: PathBuf,
        account_id: String,
        display_name: String,
        icon: PathBuf,
        identity_hex: String,
        recoverable_roots: HashMap<String, PathBuf>,
    ) -> Result<(), Box<dyn std::error::Error>> {
        if !valid_account_id(&account_id) {
            return Err("invalid account identity".into());
        }
        if !valid_display_name(&display_name) {
            return Err("invalid display name".into());
        }
        validate_registration_paths(&root, &icon)?;
        let identity = decode_identity_hex(&identity_hex)?;
        let root_path = HSTRING::from(root.as_path());
        let mut icon_resource_value = OsString::from("\"");
        icon_resource_value.push(icon.as_os_str());
        icon_resource_value.push(OsStr::new("\",0"));
        let icon_resource = HSTRING::from(&icon_resource_value);
        let display_name = HSTRING::from(display_name);
        let context = CryptographicBuffer::CreateFromByteArray(&identity)?;

        let id = sync_root_id(&account_id)?;
        let mut current_registration_is_ready = false;
        match StorageProviderSyncRootManager::GetSyncRootInformationForId(&id) {
            Ok(existing) => {
                let provider_owned = existing.ProviderId()? == PROVIDER_GUID;
                let current_state = if provider_owned {
                    current_registration_state(
                        &existing,
                        &root,
                        &display_name,
                        &icon_resource,
                        &context,
                    )?
                } else {
                    OwnedCurrentRegistrationState::UnsafeExistingPath
                };
                match existing_registration_action(true, provider_owned, current_state) {
                    ExistingRegistrationAction::KeepCurrent => {
                        current_registration_is_ready = true;
                    }
                    ExistingRegistrationAction::ReplaceCurrent => {
                        // Unregistering changes only Windows provider metadata. The old directory
                        // and every local file in it remain untouched for manual recovery.
                        unregister_owned_registration(&id)?;
                    }
                    ExistingRegistrationAction::RejectUnsafeCurrent => {
                        return Err(Box::new(UnsafeRegistrationConflict));
                    }
                    ExistingRegistrationAction::RemoveStaleOwned
                    | ExistingRegistrationAction::RetainOwnedRecovery
                    | ExistingRegistrationAction::ReportOwnedPathConflict
                    | ExistingRegistrationAction::IgnoreForeign => unreachable!(),
                }
            }
            Err(failure) if is_windows_absence_hresult(failure.code().0) => {}
            Err(failure) => return Err(failure.into()),
        }
        for existing in StorageProviderSyncRootManager::GetCurrentSyncRoots()? {
            let Ok(existing_id) = existing.Id() else {
                continue;
            };
            let Ok(existing_provider_id) = existing.ProviderId() else {
                continue;
            };
            // The exact current ID was handled above with its path recovery checks. Never make a
            // second, less-informed decision about that registration from the enumeration view.
            if existing_id == id {
                continue;
            }
            let provider_owned = existing_provider_id == PROVIDER_GUID;
            let cleanup_state = if provider_owned {
                non_current_registration_state(&existing, &existing_id, &root, &recoverable_roots)
            } else {
                OwnedCurrentRegistrationState::UnsafeExistingPath
            };
            match existing_registration_action(false, provider_owned, cleanup_state) {
                ExistingRegistrationAction::ReplaceCurrent
                | ExistingRegistrationAction::RemoveStaleOwned => {
                    // Older versions could leave account registrations behind after an interrupted
                    // setup or sign-out. Removing the registration never deletes its directory.
                    unregister_owned_registration(&existing_id)?;
                }
                ExistingRegistrationAction::KeepCurrent
                | ExistingRegistrationAction::RetainOwnedRecovery
                | ExistingRegistrationAction::IgnoreForeign => {}
                ExistingRegistrationAction::ReportOwnedPathConflict => {
                    return Err(Box::new(OwnedPathConflict));
                }
                ExistingRegistrationAction::RejectUnsafeCurrent => {
                    return Err(Box::new(UnsafeRegistrationConflict));
                }
            }
        }

        if current_registration_is_ready {
            return Ok(());
        }

        let info = StorageProviderSyncRootInfo::new()?;
        info.SetId(&id)?;
        info.SetPath(&StorageFolder::GetFolderFromPathAsync(&root_path)?.join()?)?;
        info.SetDisplayNameResource(&display_name)?;
        info.SetIconResource(&icon_resource)?;
        info.SetHydrationPolicy(StorageProviderHydrationPolicy::Progressive)?;
        info.SetHydrationPolicyModifier(
            StorageProviderHydrationPolicyModifier::AutoDehydrationAllowed,
        )?;
        info.SetPopulationPolicy(StorageProviderPopulationPolicy::Full)?;
        info.SetInSyncPolicy(
            StorageProviderInSyncPolicy::FileCreationTime
                | StorageProviderInSyncPolicy::FileLastWriteTime
                | StorageProviderInSyncPolicy::DirectoryCreationTime
                | StorageProviderInSyncPolicy::DirectoryLastWriteTime,
        )?;
        info.SetHardlinkPolicy(StorageProviderHardlinkPolicy::None)?;
        info.SetShowSiblingsAsGroup(false)?;
        info.SetVersion(&HSTRING::from(env!("CARGO_PKG_VERSION")))?;
        info.SetProviderId(PROVIDER_GUID)?;
        info.SetAllowPinning(true)?;
        info.SetContext(&context)?;
        StorageProviderSyncRootManager::Register(&info)?;
        Ok(())
    }

    fn unregister(root: PathBuf, account_id: String) -> Result<(), Box<dyn std::error::Error>> {
        if !root.is_absolute() {
            return Err("invalid sync root".into());
        }
        if !valid_account_id(&account_id) {
            return Err("invalid account identity".into());
        }
        let id = sync_root_id(&account_id)?;
        let existing = match StorageProviderSyncRootManager::GetSyncRootInformationForId(&id) {
            Ok(existing) => existing,
            Err(failure) if is_windows_absence_hresult(failure.code().0) => {
                return Err(Box::new(RegistrationNotFound));
            }
            Err(failure) => return Err(failure.into()),
        };
        if existing.ProviderId()? != PROVIDER_GUID {
            return Err(Box::new(UnsafeRegistrationConflict));
        }
        match existing.Path().and_then(|folder| folder.Path()) {
            Ok(registered_path)
                if owned_registration_path_is_safe_to_unregister(
                    registered_path_lexically_matches(&registered_path, &root),
                    || registered_path_is_missing(&registered_path),
                    || registered_path_matches(&registered_path, &root),
                )? => {}
            Ok(_) => return Err(Box::new(UnsafeRegistrationConflict)),
            Err(failure) if is_windows_absence_hresult(failure.code().0) => {}
            Err(failure) => return Err(failure.into()),
        }
        unregister_owned_registration(&id).map_err(Into::into)
    }

    pub fn run(arguments: Vec<OsString>) -> Result<(), Box<dyn std::error::Error>> {
        let _apartment = WinRtApartment::initialize()?;
        let mut values = arguments.into_iter();
        let command = values.next().and_then(|value| value.into_string().ok());
        match command.as_deref() {
            Some("self-test") if values.next().is_none() => {
                if StorageProviderSyncRootManager::IsSupported()? {
                    Ok(())
                } else {
                    Err("Windows storage-provider registration is unavailable".into())
                }
            }
            Some("register") => {
                let root = values
                    .next()
                    .map(PathBuf::from)
                    .ok_or("missing sync root")?;
                let account_id = values
                    .next()
                    .and_then(|value| value.into_string().ok())
                    .ok_or("invalid account identity")?;
                let display_name = values
                    .next()
                    .and_then(|value| value.into_string().ok())
                    .ok_or("invalid display name")?;
                let icon = values
                    .next()
                    .map(PathBuf::from)
                    .ok_or("missing icon resource")?;
                let identity_hex = values
                    .next()
                    .and_then(|value| value.into_string().ok())
                    .ok_or("invalid sync root identity")?;
                let mut recoverable_roots = HashMap::new();
                while let Some(argument) = values.next() {
                    if argument != OsStr::new(RECOVERABLE_ROOT_ARGUMENT) {
                        return Err("unexpected arguments".into());
                    }
                    let recovery_account_id = values
                        .next()
                        .and_then(|value| value.into_string().ok())
                        .ok_or("invalid recovery account identity")?;
                    if !valid_account_id(&recovery_account_id) {
                        return Err("invalid recovery account identity".into());
                    }
                    let recovery_root = values
                        .next()
                        .map(PathBuf::from)
                        .filter(|path| path.is_absolute())
                        .ok_or("invalid recovery root")?;
                    if recoverable_roots.len() >= MAX_RECOVERABLE_ROOTS {
                        return Err("too many recovery roots".into());
                    }
                    if recoverable_roots
                        .insert(recovery_account_id, recovery_root)
                        .is_some()
                    {
                        return Err("duplicate recovery account identity".into());
                    }
                }
                register(
                    root,
                    account_id,
                    display_name,
                    icon,
                    identity_hex,
                    recoverable_roots,
                )
            }
            Some("unregister") => {
                let root = values
                    .next()
                    .map(PathBuf::from)
                    .ok_or("missing sync root")?;
                let account_id = values
                    .next()
                    .and_then(|value| value.into_string().ok())
                    .ok_or("invalid account identity")?;
                if values.next().is_some() {
                    return Err("unexpected arguments".into());
                }
                unregister(root, account_id)
            }
            _ => Err("unsupported command".into()),
        }
    }
}

#[cfg(windows)]
fn main() {
    let arguments = std::env::args_os().skip(1).collect();
    if let Err(failure) = platform::run(arguments) {
        eprintln!("Windows shell registration failed.");
        std::process::exit(if failure.is::<OwnedPathConflict>() {
            3
        } else if failure.is::<RegistrationNotFound>() {
            4
        } else if failure.is::<UnsafeRegistrationConflict>() {
            5
        } else {
            1
        });
    }
}

#[cfg(not(windows))]
fn main() {
    eprintln!("This helper is only available on Windows.");
    std::process::exit(2);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_only_lowercase_sha256_account_ids() {
        assert!(valid_account_id(&"a5".repeat(32)));
        assert!(!valid_account_id(&"A5".repeat(32)));
        assert!(!valid_account_id(&"a5".repeat(31)));
        assert!(!valid_account_id(&format!("{}g", "a".repeat(63))));
    }

    #[test]
    fn accepts_only_bounded_non_control_display_names() {
        assert!(valid_display_name("Nextcloud Native - ada@cloud.example"));
        assert!(!valid_display_name(""));
        assert!(!valid_display_name("Nextcloud Native\nmalformed"));
        assert!(!valid_display_name(
            &"n".repeat(MAX_DISPLAY_NAME_CHARACTERS + 1)
        ));
    }

    #[test]
    fn decodes_bounded_lowercase_identity_hex() {
        assert_eq!(decode_identity_hex("004eff").unwrap(), vec![0, 78, 255]);
        assert!(decode_identity_hex("").is_err());
        assert!(decode_identity_hex("0").is_err());
        assert!(decode_identity_hex("AA").is_err());
        assert!(decode_identity_hex("gg").is_err());
        assert!(decode_identity_hex(&"00".repeat(MAX_SYNC_ROOT_IDENTITY_BYTES + 1)).is_err());
    }

    #[test]
    fn lowercase_hex_round_trips_binary_identity() {
        let identity = [0, 1, 15, 16, 127, 128, 255];
        let encoded = lowercase_hex(&identity);
        assert_eq!(decode_identity_hex(&encoded).unwrap(), identity);
    }

    #[test]
    fn renders_a_windows_sid_in_the_supported_registration_format() {
        let administrators_sid = [1, 2, 0, 0, 0, 0, 0, 5, 32, 0, 0, 0, 32, 2, 0, 0];
        assert_eq!(
            sid_string(&administrators_sid).as_deref(),
            Some("S-1-5-32-544")
        );
        assert_eq!(sid_string(&administrators_sid[..15]), None);
    }

    #[test]
    fn classifies_only_missing_windows_objects_as_absent() {
        assert!(is_windows_absence_hresult(0x8007_0002u32 as i32));
        assert!(is_windows_absence_hresult(0x8007_0003u32 as i32));
        assert!(is_windows_absence_hresult(0x8007_0490u32 as i32));
        assert!(!is_windows_absence_hresult(0x8007_0005u32 as i32));
        assert!(!is_windows_absence_hresult(0x8007_0057u32 as i32));
    }

    #[test]
    fn canonical_paths_identify_the_same_existing_directory() {
        let base = std::env::temp_dir().join(format!(
            "nextcloud-native-path-equivalence-{}",
            std::process::id()
        ));
        let child = base.join("child");
        std::fs::create_dir_all(&child).expect("create path-equivalence fixture");

        let equivalent = child.join(".");
        assert!(
            paths_refer_to_same_existing_entry(&child, &equivalent)
                .expect("compare equivalent paths")
        );
        assert!(
            !paths_refer_to_same_existing_entry(&base, &child).expect("compare distinct paths")
        );

        std::fs::remove_dir_all(&base).expect("remove path-equivalence fixture");
    }

    #[test]
    fn removes_only_stale_owned_registrations() {
        assert_eq!(
            existing_registration_action(true, true, OwnedCurrentRegistrationState::Ready),
            ExistingRegistrationAction::KeepCurrent
        );
        assert_eq!(
            existing_registration_action(true, true, OwnedCurrentRegistrationState::Replaceable),
            ExistingRegistrationAction::ReplaceCurrent
        );
        assert_eq!(
            existing_registration_action(
                true,
                true,
                OwnedCurrentRegistrationState::UnsafeExistingPath,
            ),
            ExistingRegistrationAction::RejectUnsafeCurrent
        );
        assert_eq!(
            existing_registration_action(false, true, OwnedCurrentRegistrationState::Replaceable,),
            ExistingRegistrationAction::RemoveStaleOwned
        );
        assert_eq!(
            existing_registration_action(
                false,
                true,
                OwnedCurrentRegistrationState::UnsafeExistingPath,
            ),
            ExistingRegistrationAction::RetainOwnedRecovery
        );
        assert_eq!(
            existing_registration_action(
                false,
                true,
                OwnedCurrentRegistrationState::RequestedRootConflict,
            ),
            ExistingRegistrationAction::ReportOwnedPathConflict
        );
        assert_eq!(
            existing_registration_action(false, false, OwnedCurrentRegistrationState::Ready),
            ExistingRegistrationAction::IgnoreForeign
        );
        assert_eq!(
            existing_registration_action(true, false, OwnedCurrentRegistrationState::Ready),
            ExistingRegistrationAction::RejectUnsafeCurrent
        );
    }

    #[test]
    fn distinguishes_missing_same_and_different_existing_registration_paths() {
        let base = std::env::temp_dir().join(format!(
            "nextcloud-native-registration-path-state-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("system clock after Unix epoch")
                .as_nanos()
        ));
        let registered = base.join("registered");
        let requested = base.join("requested");
        std::fs::create_dir_all(&registered).expect("create registered path fixture");
        std::fs::create_dir_all(&requested).expect("create requested path fixture");

        assert_eq!(
            registered_path_state(&base.join("missing"), &requested)
                .expect("classify missing registration path"),
            RegisteredPathState::Missing
        );
        assert_eq!(
            registered_path_state(&registered, &registered)
                .expect("classify matching registration path"),
            RegisteredPathState::SameExisting
        );
        assert_eq!(
            registered_path_state(&registered, &requested)
                .expect("classify different registration path"),
            RegisteredPathState::DifferentExisting
        );
        let account_id = "a5".repeat(32);
        let mut recoverable_roots = std::collections::HashMap::new();
        recoverable_roots.insert(account_id.clone(), registered.clone());
        assert!(recoverable_root_matches(
            &recoverable_roots,
            &account_id,
            &registered,
        ));
        assert!(!recoverable_root_matches(
            &recoverable_roots,
            &account_id,
            &requested,
        ));

        std::fs::remove_dir_all(&base).expect("remove registration path fixture");
    }

    #[test]
    fn exact_registration_path_bypasses_unavailable_filesystem_probes() {
        assert!(
            owned_registration_path_is_safe_to_unregister(
                true,
                || Err(std::io::Error::other("cloud provider is unavailable")),
                || panic!("an exact registered path must not be canonicalized"),
            )
            .expect("accept exact registered path")
        );
    }

    #[test]
    fn normalizes_equivalent_windows_registration_path_forms() {
        fn ascii_case_insensitive(first: &[u16], second: &[u16]) -> bool {
            first.len() == second.len()
                && first
                    .iter()
                    .zip(second)
                    .all(|(left, right)| ascii_windows_path_unit_eq(*left, *right))
        }

        let extended: Vec<u16> = r"\\?\C:\fixtures\Nextcloud Native\account-v2\"
            .encode_utf16()
            .collect();
        let ordinary: Vec<u16> = r"c:/fixtures/nextcloud native/account-v2"
            .encode_utf16()
            .collect();
        assert!(normalized_windows_paths_match(
            &extended,
            &ordinary,
            ascii_case_insensitive,
        ));

        let extended_unc: Vec<u16> = r"\\?\UNC\server\share\account-v2\".encode_utf16().collect();
        let ordinary_unc: Vec<u16> = r"\\SERVER\SHARE\ACCOUNT-V2".encode_utf16().collect();
        assert!(normalized_windows_paths_match(
            &extended_unc,
            &ordinary_unc,
            ascii_case_insensitive,
        ));
    }

    #[test]
    fn rejects_lexically_different_windows_registration_paths() {
        fn ascii_case_insensitive(first: &[u16], second: &[u16]) -> bool {
            first.len() == second.len()
                && first
                    .iter()
                    .zip(second)
                    .all(|(left, right)| ascii_windows_path_unit_eq(*left, *right))
        }

        let registered: Vec<u16> = r"C:\fixtures\Nextcloud Native\old-account-v2"
            .encode_utf16()
            .collect();
        let requested: Vec<u16> = r"C:\fixtures\Nextcloud Native\account-v2"
            .encode_utf16()
            .collect();
        assert!(!normalized_windows_paths_match(
            &registered,
            &requested,
            ascii_case_insensitive,
        ));
    }

    #[test]
    fn non_exact_registration_paths_retain_fail_closed_checks() {
        let unavailable = owned_registration_path_is_safe_to_unregister(
            false,
            || Err(std::io::Error::other("cloud provider is unavailable")),
            || Ok(true),
        );
        assert!(unavailable.is_err());
        assert!(
            owned_registration_path_is_safe_to_unregister(
                false,
                || Ok(true),
                || { panic!("a missing registered path needs no canonical comparison") }
            )
            .expect("accept missing registration path")
        );
        assert!(
            !owned_registration_path_is_safe_to_unregister(false, || Ok(false), || Ok(false))
                .expect("reject a different existing registration path")
        );
    }

    #[test]
    fn extracts_only_owned_well_formed_account_ids_from_sync_root_ids() {
        let account_id = "a5".repeat(32);
        assert_eq!(
            account_id_from_sync_root_id(&format!(
                "Obiente.NextcloudNative!S-1-5-21-1000!{account_id}"
            )),
            Some(account_id.as_str())
        );
        assert_eq!(
            account_id_from_sync_root_id(&format!("Other.Provider!S-1-5-21-1000!{account_id}")),
            None
        );
        assert_eq!(
            account_id_from_sync_root_id("Obiente.NextcloudNative!S-1-5-21-1000!invalid"),
            None
        );
    }
}
