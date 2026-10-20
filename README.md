> [!CAUTION]
> The only official places to download Chevstrap Extended is this GitHub repository. Any other websites offering downloads or claiming to be us are not controlled by us.

<div align="center">

<p align="center">
    <img src="assets/logo-light.png#gh-light-mode-only" width="450">
    <img src="assets/logo-dark.png#gh-dark-mode-only" width="450">
</p>

[![License][badge-repo-license]][repo-license]
[![Downloads (Total)][badge-repo-downloads-total]][repo-releases]
[![Downloads (Latest)][badge-repo-downloads]][repo-releases]
[![Version][badge-repo-latest]][repo-latest]
[![Stars][badge-repo-stars]][repo-stargazer]

**A third-party Android launcher for Roblox, based on Bloxstrap and specifically designed for Android.**

----

</div>

## About

Chevstrap Extended is a fork of [Chevstrap](https://github.com/FrosSky/Chevstrap), which is itself based on [Bloxstrap](https://github.com/bloxstraplabs/bloxstrap). It keeps the original feature set and adds more ways for the app to reach Roblox's data, along with a guided first-run setup.

The original Chevstrap reads Roblox's files by running both apps inside the same Virtual Space. Chevstrap Extended can instead use **Shizuku** or **Root** on supported devices, so a Virtual Space is no longer the only option.

> [!NOTE]
> Chevstrap Extended uses the application ID `com.chevstrap.extended`. It installs alongside the original Chevstrap, with separate settings and data.

## Features

### Launcher

- Launch Roblox with a configurable preferred app (Global, VNG, or Galaxy Store).
- Discord Rich Presence, so your friends can see which experience you're playing.
- Optional custom Rich Presence set by games, including details, state, images, and buttons.
- Server location lookup via the [ipinfo.io](https://ipinfo.io/) API.
- Game history that lets you rejoin a previous server, unless it has expired, is unavailable, or is a reserved server.

### Extended access modes

- **Shizuku** — follow Roblox's log output as the shell user, with no root required.  
- **Root** — read Roblox's private directory through root, with no Virtual Space required.
- **Guided first-run setup** — pick and configure an access mode when you first open the app.
- **Opt-in permissions** — root and Shizuku are off by default; enabling one triggers the matching permission prompt.
- **Automatic cleanup** — the activity watcher stops itself after Roblox closes.

### Access modes

| Mode | Required | How it works |
| --- | --- | --- |
| Direct | A Virtual Space | Reads Roblox's files directly while both apps run in the same Virtual Space. |
| Root | A rooted device | Reads Roblox's private directory as root. |
| Shizuku | [Shizuku](https://github.com/thedjchi/Shizuku) installed and running | Follows Roblox's logs as the shell user, without root. |

Direct access is used automatically when available. Root and Shizuku are opt-in and can be enabled in **Settings → Launcher**, or from the first-run setup screen.

> [!IMPORTANT]
> Root and Shizuku are optional. If you don't need them, the app still works on a non-rooted device through a Virtual Space or Shizuku. Rooting your device is never required and not recommended.

## Installation

1. Download the [latest release of Chevstrap Extended](https://github.com/oscski/Chevstrap-Extended/releases/latest).
2. Install the APK on your Android device.
3. Open Chevstrap Extended and follow the setup screen.

Because the application ID is separate from the original Chevstrap, both can be installed at the same time. Their settings and game history are not shared.

## System Requirements

- OS version: Android 8.0 through 17
- RAM: 3 GB minimum
- Graphics API: OpenGL ES 3.0 or later
- Shizuku mode only: the Shizuku app installed and running, with permission granted

## Build

> Prerequisites:
- Android Studio
- Familiarity with Gradle and Kotlin

> Clone the project
```console
git clone https://github.com/oscski/Chevstrap-Extended.git
```
> Building
- Open Android Studio
- Import the project
- Click **Build and Run**

Release builds are produced by the [CI workflow][repo-actions].

## F.A.Q

#### Q: Is this malware?

A: No. The source code is public, so you can check it yourself. Only download Chevstrap from the [official FrosSky repository](https://github.com/FrosSky/Chevstrap) and Chevstrap Extended from this repository.

#### Q: Is this the official Chevstrap?

A: No. Chevstrap Extended is a fork of [Chevstrap](https://github.com/FrosSky/Chevstrap). Please don't report issues about Chevstrap Extended to the original project.

#### Q: Can I get banned for using Chevstrap Extended?

A: No. As long as you have never used exploits, including modified Roblox clients or process injectors, and only run the native Roblox client from [Google Play](https://play.google.com/store/apps/details?id=com.roblox.client), and your Virtual Space doesn't improperly manipulate the Roblox client's memory, you should be fine. [Learn more here](https://github.com/oscski/Chevstrap-Extended/wiki/Why-you-can't-get-banned-by-Chevstrap).

#### Q: Does Chevstrap Extended require root access?

A: No. It works on non-rooted devices through a Virtual Space or Shizuku. Root is an optional convenience for devices that are already rooted. Keep in mind that Roblox does not officially support rooted or jailbroken devices, so use root mode at your own risk. See [Roblox's anti-cheat messages](https://en.help.roblox.com/hc/en-us/articles/24275616578708-Anti-cheat-Messages).

#### Q: What is Shizuku, and why would I use it?

A: [Shizuku](https://shizuku.rikka.app/) grants apps limited shell access without root. In Chevstrap Extended it lets the app follow Roblox's logs, so activity tracking, server location, and Discord Rich Presence work without a Virtual Space.

## Credits

- [FrosSky](https://github.com/FrosSky) — Original Chevstrap
- [Bloxstrap contributors](https://github.com/bloxstraplabs/bloxstrap) — The project Chevstrap is based on
- [oscski](https://github.com/oscski) — Chevstrap Extended

## License

Released under the [MIT License][repo-license].

[badge-repo-license]: https://img.shields.io/github/license/oscski/Chevstrap-Extended?style=flat-square&color=28BC88
[badge-repo-downloads]: https://img.shields.io/github/downloads/oscski/Chevstrap-Extended/latest/total?style=flat-square&color=28BC88
[badge-repo-downloads-total]: https://img.shields.io/github/downloads/oscski/Chevstrap-Extended/total?style=flat-square&color=28BC88
[badge-repo-latest]: https://img.shields.io/github/v/release/oscski/Chevstrap-Extended?style=flat-square&color=28BC88
[badge-repo-stars]: https://img.shields.io/github/stars/oscski/Chevstrap-Extended?style=flat-square&color=28BC88

[repo-license]: https://github.com/oscski/Chevstrap-Extended/blob/main/LICENSE
[repo-actions]: https://github.com/oscski/Chevstrap-Extended/actions
[repo-releases]: https://github.com/oscski/Chevstrap-Extended/releases
[repo-latest]: https://github.com/oscski/Chevstrap-Extended/releases/latest
[repo-stargazer]: https://github.com/oscski/Chevstrap-Extended/stargazers
