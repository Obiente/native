#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
const PROVIDER_ID: &str = "Obiente.NextcloudNative";
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
#[derive(Debug, PartialEq, Eq)]
enum ExistingRegistrationAction {
    KeepCurrent,
    ReplaceCurrent,
    RemoveStaleOwned,
    IgnoreForeign,
    RejectForeignCurrent,
}

#[cfg(any(windows, test))]
fn existing_registration_action(
    current_id: bool,
    provider_owned: bool,
    current_metadata: bool,
) -> ExistingRegistrationAction {
    match (current_id, provider_owned, current_metadata) {
        (true, true, true) => ExistingRegistrationAction::KeepCurrent,
        (true, true, false) => ExistingRegistrationAction::ReplaceCurrent,
        (true, false, _) => ExistingRegistrationAction::RejectForeignCurrent,
        (false, true, _) => ExistingRegistrationAction::RemoveStaleOwned,
        (false, false, _) => ExistingRegistrationAction::IgnoreForeign,
    }
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
        ExistingRegistrationAction, PROVIDER_ID, RegistrationNotFound, UnsafeRegistrationConflict,
        decode_identity_hex, existing_registration_action, is_windows_absence_hresult,
        paths_refer_to_same_existing_entry, sid_string, valid_account_id, valid_display_name,
    };
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
        if path == &HSTRING::from(requested) {
            return Ok(true);
        }
        paths_refer_to_same_existing_entry(&PathBuf::from(path.to_os_string()), requested)
    }

    fn current_registration_metadata_matches(
        existing: &StorageProviderSyncRootInfo,
        root: &Path,
        display_name: &HSTRING,
        icon_resource: &HSTRING,
        context: &windows::Storage::Streams::IBuffer,
    ) -> bool {
        let matches = || -> WindowsResult<bool> {
            let existing_path = existing.Path()?.Path()?;
            if registered_path_is_missing(&existing_path).unwrap_or(false)
                || !registered_path_matches(&existing_path, root).unwrap_or(false)
            {
                return Ok(false);
            }
            Ok(existing.DisplayNameResource()? == *display_name
                && existing.IconResource()? == *icon_resource
                && CryptographicBuffer::Compare(&existing.Context()?, context)?)
        };
        // Once the stable ID and provider GUID prove ownership, unreadable or incomplete
        // properties are stale metadata to replace, not a reason to strand the account.
        matches().unwrap_or(false)
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
                let current_metadata = provider_owned
                    && current_registration_metadata_matches(
                        &existing,
                        &root,
                        &display_name,
                        &icon_resource,
                        &context,
                    );
                match existing_registration_action(true, provider_owned, current_metadata) {
                    ExistingRegistrationAction::KeepCurrent => {
                        current_registration_is_ready = true;
                    }
                    ExistingRegistrationAction::ReplaceCurrent => {
                        // Unregistering changes only Windows provider metadata. The old directory
                        // and every local file in it remain untouched for manual recovery.
                        unregister_owned_registration(&id)?;
                    }
                    ExistingRegistrationAction::RejectForeignCurrent => {
                        return Err(Box::new(UnsafeRegistrationConflict));
                    }
                    ExistingRegistrationAction::RemoveStaleOwned
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
            match existing_registration_action(
                existing_id == id,
                existing_provider_id == PROVIDER_GUID,
                current_registration_is_ready,
            ) {
                ExistingRegistrationAction::ReplaceCurrent
                | ExistingRegistrationAction::RemoveStaleOwned => {
                    // Older versions could leave account registrations behind after an interrupted
                    // setup or sign-out. Removing the registration never deletes its directory.
                    unregister_owned_registration(&existing_id)?;
                }
                ExistingRegistrationAction::KeepCurrent
                | ExistingRegistrationAction::IgnoreForeign => {}
                ExistingRegistrationAction::RejectForeignCurrent => {
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
            Ok(registered_path) if registered_path_is_missing(&registered_path)? => {}
            Ok(registered_path) if registered_path_matches(&registered_path, &root)? => {}
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
                if values.next().is_some() {
                    return Err("unexpected arguments".into());
                }
                register(root, account_id, display_name, icon, identity_hex)
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
            existing_registration_action(true, true, true),
            ExistingRegistrationAction::KeepCurrent
        );
        assert_eq!(
            existing_registration_action(true, true, false),
            ExistingRegistrationAction::ReplaceCurrent
        );
        assert_eq!(
            existing_registration_action(false, true, false),
            ExistingRegistrationAction::RemoveStaleOwned
        );
        assert_eq!(
            existing_registration_action(false, false, false),
            ExistingRegistrationAction::IgnoreForeign
        );
        assert_eq!(
            existing_registration_action(true, false, false),
            ExistingRegistrationAction::RejectForeignCurrent
        );
    }
}
