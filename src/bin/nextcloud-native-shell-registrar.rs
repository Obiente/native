#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
const PROVIDER_ID: &str = "Obiente.NextcloudNative";
#[cfg(any(windows, test))]
const ACCOUNT_ID_LENGTH: usize = 64;
#[cfg(any(windows, test))]
const MAX_SYNC_ROOT_IDENTITY_BYTES: usize = 4_096;

#[cfg(any(windows, test))]
fn valid_account_id(value: &str) -> bool {
    value.len() == ACCOUNT_ID_LENGTH
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
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
    use super::{PROVIDER_ID, decode_identity_hex, sid_string, valid_account_id};
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
        icon: PathBuf,
        identity_hex: String,
    ) -> Result<(), Box<dyn std::error::Error>> {
        if !valid_account_id(&account_id) {
            return Err("invalid account identity".into());
        }
        validate_registration_paths(&root, &icon)?;
        let identity = decode_identity_hex(&identity_hex)?;
        let root_path = HSTRING::from(root.as_path());
        let mut icon_resource_value = OsString::from("\"");
        icon_resource_value.push(icon.as_os_str());
        icon_resource_value.push(OsStr::new("\",0"));
        let icon_resource = HSTRING::from(&icon_resource_value);
        let display_name = HSTRING::from("Nextcloud Native");
        let context = CryptographicBuffer::CreateFromByteArray(&identity)?;

        let id = sync_root_id(&account_id)?;
        if let Ok(existing) = StorageProviderSyncRootManager::GetSyncRootInformationForId(&id) {
            if existing.Path()?.Path()? == root_path
                && existing.DisplayNameResource()? == display_name
                && existing.IconResource()? == icon_resource
                && CryptographicBuffer::Compare(&existing.Context()?, &context)?
            {
                return Ok(());
            }
            StorageProviderSyncRootManager::Unregister(&id)?;
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

    fn unregister(account_id: String) -> Result<(), Box<dyn std::error::Error>> {
        if !valid_account_id(&account_id) {
            return Err("invalid account identity".into());
        }
        StorageProviderSyncRootManager::Unregister(&sync_root_id(&account_id)?)?;
        Ok(())
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
                register(root, account_id, icon, identity_hex)
            }
            Some("unregister") => {
                let account_id = values
                    .next()
                    .and_then(|value| value.into_string().ok())
                    .ok_or("invalid account identity")?;
                if values.next().is_some() {
                    return Err("unexpected arguments".into());
                }
                unregister(account_id)
            }
            _ => Err("unsupported command".into()),
        }
    }
}

#[cfg(windows)]
fn main() {
    let arguments = std::env::args_os().skip(1).collect();
    if platform::run(arguments).is_err() {
        eprintln!("Windows shell registration failed.");
        std::process::exit(1);
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
}
