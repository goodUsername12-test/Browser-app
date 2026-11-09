# Fable Browser for Windows

The Windows version of Fable Browser. It lives next to the Android app in this repository and does not change any of the Android files.

It is a C# / WPF app that uses the Microsoft Edge WebView2 engine, which is already part of Windows 10 and 11. One window, many tabs, the same privacy features and icon as the Android version.

## Download

Every push to `main` or `master` that touches `desktop/` builds the app on GitHub Actions.

1. Open the **Actions** tab, pick the latest **Build Windows** run.
2. Download one of the artifacts at the bottom of the run page.

| Artifact | Size | Needs |
| --- | --- | --- |
| `FableBrowser-win-x64` | small | [.NET 10 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/10.0) (Windows asks to install it on first start if missing) |
| `FableBrowser-win-x64-standalone` | about 70 MB | nothing |
| `FableBrowser-win-arm64` / `-standalone` | same as above | for ARM PCs (Snapdragon) |

Unzip and run `FableBrowser.exe`. Keep the files in the folder together.

To publish a GitHub release, push a tag like `desktop-v1.0.0`. The workflow builds everything and attaches the zips and a `SHA256SUMS.txt` file to the release.

## Features

**Tabs**: normal, incognito and super incognito tabs, tab restore, reopen closed tab, tab sleeping and freezing to save memory, and freezing of other tabs while a game runs full screen.

**Privacy**: EasyList and EasyPrivacy blocking (updated weekly), HTTPS-only mode, tracking parameter removal, Global Privacy Control, pop-up blocking, WebRTC local IP hiding, secure DNS, engine tracking prevention, per-site shield exceptions.

**Incognito**: memory-only profile, separate cookies per tab, fingerprint shield, UTC time zone, optional JavaScript off, PIN lock, auto close after time in the background, SOCKS / HTTP proxy (works with Tor on port 9050).

**Super incognito**: every tab gets its own made-up desktop identity (browser version, OS, screen, CPU, memory, languages, time zone, graphics card) plus its own storage. Game mode keeps canvas, audio and screen real for WebGL games.

**Games**: GPU rasterization, optional uncapped frame rate, forced GPU acceleration, pointer lock and gamepads. Normal tabs never get scripts injected, so games with anti-cheat see a normal Chromium browser.

**Tools**: Fable Console (Ctrl+Shift+K) with saved snippets, full developer tools (F12), find in page, zoom, print, history, bookmarks (HTML import and export), downloads, permission prompts, dark and light themes.

## Keyboard shortcuts

| Keys | Action |
| --- | --- |
| Ctrl+T / Ctrl+Shift+T | New tab / reopen closed tab |
| Ctrl+Shift+N | New incognito tab |
| Ctrl+Alt+N | New super incognito tab |
| Ctrl+W | Close tab |
| Ctrl+Tab, Ctrl+1 to 9 | Switch tabs |
| Ctrl+L, Alt+D, F6 | Address bar |
| Alt+Enter in the address bar | Open in a new tab |
| Ctrl+F, F3 | Find in page |
| Ctrl+D | Bookmark |
| Ctrl+H / Ctrl+J / Ctrl+Shift+O | History / downloads / bookmarks |
| F5, Ctrl+R | Reload |
| F11 | Full screen |
| Ctrl+Shift+K | Fable Console |
| F12 | Developer tools |
| Ctrl+, | Settings |
| Ctrl + / - / 0 | Zoom |
| Shift+Esc | Engine task manager |

## Where data is stored

By default in `%LOCALAPPDATA%\FableBrowser`. For a portable copy on a USB stick, create an empty file named `portable.txt` next to `FableBrowser.exe`; data then goes to a `FableData` folder beside it.

Fable sends no telemetry. Error logs stay in the `Logs` folder on your PC.

## Windows SmartScreen and antivirus

New programs that are not code signed can show "Windows protected your PC". Click **More info** and **Run anyway**. The builds are made in public on GitHub Actions from this source code, are not packed or compressed, and every artifact has a `SHA256SUMS.txt` you can compare.

If an antivirus flags a build, check the detection name. Generic names such as `IDP.Generic`, `Win32:Evo-gen` or `FileRepMalware` usually mean "new and unknown". You can report a false positive to the vendor (Avast: https://www.avast.com/false-positive-file-form.php, Microsoft: https://www.microsoft.com/wdsi/filesubmission).

Signing the exe with a code signing certificate (for example through Azure Trusted Signing or SignPath for open source projects) removes most of these warnings over time.

## Building on your own PC

Install the [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0), then from the repository root:

```
dotnet build desktop/FableBrowser.slnx -c Release
dotnet run --project desktop/tests/FableBrowser.SelfTest -c Release
dotnet run --project desktop/src/FableBrowser -c Release
```

Publish like the workflow does:

```
dotnet publish desktop/src/FableBrowser/FableBrowser.csproj -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -p:PublishReadyToRun=true -o dist
```

The project also builds from Linux or a Codespace (`EnableWindowsTargeting` is on), but the app only runs on Windows.

## Project layout

```
desktop/
  FableBrowser.slnx
  src/FableBrowser/
    App.xaml, MainWindow.xaml      window, tab strip, toolbar, panels
    MainWindow*.cs                 tabs, address bar, menus, find, console, lock
    Browser/                       WebView2 tabs, engine setup, tab sleeping
    Core/                          settings, URL rules, ad blocker, identities, history and bookmarks
    UI/                            settings, library and dialogs
    Resources/                     filter lists, new tab page, fingerprint shield
    Assets/                        app icon
  tests/FableBrowser.SelfTest/     checks run by the workflow before publishing
```

## Android workflow

The Android workflow (`build-apk.yml`) still builds the APK as before. If you do not want APK builds when only `desktop/` changes, add this under its `push:` and `pull_request:` triggers:

```yaml
    paths-ignore:
      - 'desktop/**'
      - '.github/workflows/build-windows.yml'
```
