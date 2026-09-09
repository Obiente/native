export const guidePlatformHubs = [
  {
    slug: "android",
    label: "Android",
    device: "Mobile",
    title: "nati.ve guides for Android",
    description:
      "Set up nati.ve on Android, keep files available offline, sync device folders, back up media, and use Calendar with phone-specific instructions.",
    summary:
      "Phone and tablet workflows with Android permissions, System Files integration, durable background work, and touch-first navigation.",
  },
  {
    slug: "desktop",
    label: "Desktop",
    device: "Desktop",
    title: "nati.ve desktop guides",
    description:
      "Use nati.ve on Linux and Windows with desktop navigation, folder sync, native file integration, and platform-specific setup guidance.",
    summary:
      "Shared Linux and Windows workflows, with separate operating-system guides wherever installation, credentials, or file integration differs.",
  },
  {
    slug: "linux",
    label: "Linux",
    device: "Desktop",
    title: "nati.ve guides for Linux",
    description:
      "Install and use nati.ve on Linux, configure desktop folder sync, and understand Secret Service and filesystem integration in the current alpha.",
    summary:
      "Linux-specific installation, Secret Service credentials, recurring folder sync, and the native filesystem mount.",
  },
  {
    slug: "windows",
    label: "Windows",
    device: "Desktop",
    title: "nati.ve guides for Windows",
    description:
      "Install the nati.ve Windows alpha safely and use Credential Manager and Cloud Files placeholders in File Explorer.",
    summary:
      "Windows-specific MSI trust guidance, Credential Manager storage, and Cloud Files behavior in File Explorer.",
  },
];

export function guidePlatformHubForPath(path) {
  return guidePlatformHubs.find((hub) => path === `/guides/${hub.slug}/`) ?? null;
}

export function guidesForPlatformHub(guides, hub) {
  if (hub.slug === "desktop") return guides.filter((guide) => guide.device === "Desktop");
  return guides.filter(
    (guide) => guide.platform === hub.label || guide.platforms.includes(hub.label),
  );
}
