%global _build_id_links none

Summary: One native client for your complete Nextcloud account
Name: APPLICATION_PACKAGE
Version: APPLICATION_VERSION
Release: APPLICATION_RELEASE
License: AGPL-3.0-or-later
Vendor: Obiente
URL: https://nc-native.obiente.dev/

%if "xAPPLICATION_PREFIX" != "x"
Prefix: APPLICATION_PREFIX
%endif

Provides: APPLICATION_PACKAGE
Group: Applications/Internet

Autoprov: 0
Autoreq: 0
%if "xPACKAGE_DEFAULT_DEPENDENCIES" != "x" || "xPACKAGE_CUSTOM_DEPENDENCIES" != "x"
Requires: PACKAGE_DEFAULT_DEPENDENCIES PACKAGE_CUSTOM_DEPENDENCIES
%endif

%define __jar_repack %{nil}
%define package_filelist %{_tmppath}/%{name}.files
%define app_filelist %{_tmppath}/%{name}.app.files
%define filesystem_filelist %{_tmppath}/%{name}.filesystem.files
%define default_filesystem / /opt /usr /usr/bin /usr/lib /usr/local /usr/local/bin /usr/local/lib

%description
Nextcloud Native brings files, photos, media, collaboration, and installed
Nextcloud apps together in one coherent native desktop client.

%global __os_install_post %{nil}

%prep

%build

%install
rm -rf %{buildroot}
install -d -m 755 %{buildroot}APPLICATION_DIRECTORY
cp -r %{_sourcedir}APPLICATION_DIRECTORY/* %{buildroot}APPLICATION_DIRECTORY
if [ "$(echo %{_sourcedir}/lib/systemd/system/*.service)" != '%{_sourcedir}/lib/systemd/system/*.service' ]; then
  install -d -m 755 %{buildroot}/lib/systemd/system
  cp %{_sourcedir}/lib/systemd/system/*.service %{buildroot}/lib/systemd/system
fi
install -d -m 755 %{buildroot}/usr/share/metainfo
printf '%s' 'APPSTREAM_XML_BASE64' | base64 --decode > \
  %{buildroot}/usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml
%if "xAPPLICATION_LICENSE_FILE" != "x"
  %define license_install_file %{_defaultlicensedir}/%{name}-%{version}/%{basename:APPLICATION_LICENSE_FILE}
  install -d -m 755 "%{buildroot}%{dirname:%{license_install_file}}"
  install -m 644 "APPLICATION_LICENSE_FILE" "%{buildroot}%{license_install_file}"
%endif
(cd %{buildroot} && find . -path ./lib/systemd -prune -o -type d -print) | sed -e 's/^\.//' -e '/^$/d' | sort > %{app_filelist}
{ rpm -ql filesystem || echo %{default_filesystem}; } | sort > %{filesystem_filelist}
comm -23 %{app_filelist} %{filesystem_filelist} > %{package_filelist}
sed -i -e 's/.*/%dir "&"/' %{package_filelist}
(cd %{buildroot} && find . -not -type d) | sed -e 's/^\.//' -e 's/.*/"&"/' >> %{package_filelist}
%if "xAPPLICATION_LICENSE_FILE" != "x"
  sed -i -e 's|"%{license_install_file}"||' -e '/^$/d' %{package_filelist}
%endif

%files -f %{package_filelist}
%if "xAPPLICATION_LICENSE_FILE" != "x"
  %license "%{license_install_file}"
%endif

%post
package_type=rpm
LAUNCHER_AS_SERVICE_SCRIPTS
DESKTOP_COMMANDS_INSTALL
LAUNCHER_AS_SERVICE_COMMANDS_INSTALL

%pre
package_type=rpm
LAUNCHER_AS_SERVICE_SCRIPTS
if [ "$1" = 2 ]; then
  true; LAUNCHER_AS_SERVICE_COMMANDS_UNINSTALL
fi

%preun
package_type=rpm
DESKTOP_SCRIPTS
LAUNCHER_AS_SERVICE_SCRIPTS
DESKTOP_COMMANDS_UNINSTALL
LAUNCHER_AS_SERVICE_COMMANDS_UNINSTALL

%clean
