# Hs Android Package Center

Hs includes a small, optional catalog of Termux packages that are useful on Android. The catalog is mirrored at:

- `hasikuii/termux-packages/hs-android-packages.json`
- `hasikuii/Hs/app/src/main/assets/hs-android-packages.json`

## How to use

1. Open the file manager's **Công cụ quản lý tệp** menu.
2. Select **Trung tâm package Android**.
3. Choose a category and select only the packages you need.
4. Review the command and caveats, then send it to the active terminal.
5. Run it from the Termux Host shell. Exit Debian PRoot first.

The package center does not execute commands in the background. It sends a reviewed `pkg update && pkg install -y ...` command to the currently running terminal session. The user can inspect the command before confirming.

## Curated packages

| Category | Packages | Android notes |
|---|---|---|
| Android integration | `android-tools`, `termux-api` | ADB/Fastboot remains subject to Android USB/device permissions. `termux-api` also requires the separate Termux:API app from the same signing source. |
| Linux environment | `proot-distro`, `termux-services` | PRoot is user-space only. Android background execution limits still apply to daemons. |
| File tools | `7zip`, `fd`, `aria2` | Operate only on paths accessible to the Termux app. |
| Shell productivity | `fish`, `fzf`, `jq` | Optional user-space tools; Bash and the default package manager are not replaced. |

## Important architecture decisions

- Keep Termux's original `pkg`, `apt`, `dpkg`, shell and bootstrap behavior.
- Do not bundle arbitrary Debian binaries or install packages into the APK's private files at runtime.
- Install optional packages through the user's configured Termux APT repository.
- The catalog is not itself an APT repository and does not publish binary packages. Package recipes already present in the fork remain maintained by the Termux build system.
- Do not add root-only packages to this Android-friendly catalog.
- Never assume Shizuku, root, privileged mounts, kernel modules or unrestricted Android storage access.

## Package source and build constraints

Termux packages must be built for Android/Bionic using the Termux package build system, Android API-level constraints and architecture-specific toolchains. A Debian `.deb` is not interchangeable with a Termux package.

See:
- https://github.com/hasikuii/termux-packages
- https://github.com/termux/termux-packages/wiki/Creating-new-package
- https://github.com/termux/termux-packages/wiki/Package-Management
