# PocketDev

A minimal code editor for Android built on [sora-editor](https://github.com/Rosemoe/sora-editor) 0.23.6.

Package name: `com.bfoxnet.pocketdev`

## Features
- **50 languages** with syntax highlighting: JavaScript/TypeScript (incl. JSX/TSX), Python, HTML, CSS/SCSS/Sass, Shell, Markdown/MDX, JSON, YAML, XML, SQL, Java, Kotlin, Groovy/Gradle, Smali, C/C++, C#, PHP, Go, Rust, Dart, TOML, INI, Properties, GraphQL, Vue, Svelte, Ruby, Lua, Dockerfile, Swift, Objective-C/MATLAB (`.m`), R, Perl, Scala, Elixir, Erlang, Haskell, Clojure, F#, PowerShell, Batch
  - TextMate grammars in `app/src/main/assets/textmate/` (from VS Code, textmate bundles, vetur, svelte-language-tools — all open licensed)
  - Monarch grammars from the `monarch-language-pack` artifact
- **20 popular editor themes** (VS Code format JSONs, work with both engines): One Dark Pro, Dracula, Monokai, Night Owl, Tokyo Night, Catppuccin Mocha, Material Theme, Ayu Dark, Nord, Gruvbox Dark, Solarized Dark, SynthWave '84, Cobalt2, Andromeda, Panda, Shades of Purple, Horizon, Rosé Pine, Vitesse Dark — plus Darcula and Quiet Light
- 4 bundled monospace fonts: JetBrains Mono, Fira Code, Google Sans Code, Roboto Mono
- Settings page for theme / font / font size / word wrap
- Find-in-file with match-case and regex toggles
- Open/save through the Storage Access Framework, plus `ACTION_VIEW` intent support
- Undo/redo, word wrap toggle, symbol row above the keyboard
- Firebase Crashlytics wired up (see below)

## Firebase Crashlytics
The project ships with a **placeholder** `app/google-services.json` so the build
works out of the box; crash reporting is inert until you use a real config:

1. Create a project at https://console.firebase.google.com with an Android app
   named `com.bfoxnet.pocketdev`.
2. Download its `google-services.json` and replace `app/google-services.json`.
3. Rebuild. Crashes now upload to your Firebase console.

## Project layout
```
app/src/main/java/com/bfoxnet/pocketdev/
  MainActivity.kt        UI wiring, file open/save, search bar
  EditorController.kt    sora-editor setup, themes, language mapping
  SettingsActivity.kt    theme/font/size/wrap preferences
  Settings.kt            SharedPreferences-backed settings
  OpenedFile.kt          tiny file model + SAF helpers
app/src/main/assets/textmate/   TextMate grammars + the 22 theme JSONs
colab/build_apk.ipynb           notebook for building on Google Colab
```

## Build on Colab
1. Run `./make_zip.sh` to produce `pocketdev.zip`.
2. Open `colab/build_apk.ipynb` in Google Colab and upload `pocketdev.zip`.
3. Run all cells; download `app/build/outputs/apk/debug/app-debug.apk`.

## Build locally (if you have the Android SDK)
```
./gradlew assembleDebug
```

## Notes
- sora-editor's own APIs and settings are taken from its 0.23.6 sources and the
  bundled sample app — grammars, theme registry usage, and editor props all
  mirror the official sample.
- The launcher icon is an adaptive icon (API 26+) with a plain vector fallback
  for API 24/25.
