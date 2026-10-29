# Tetris

A lightweight Tetris game for Android, written in Kotlin with no third-party libraries (~50 KB APK).

## Features

- Modern guideline rules: SRS rotation with wall kicks, 7-bag randomizer, ghost piece, hold, 3-piece preview, lock delay
- Scoring: 100 / 300 / 500 / 800 × level for 1–4 lines, +1 per soft-drop row, +2 per hard-drop row
- Level up every 10 lines with guideline gravity curve; best score saved on device
- Full-screen, auto-pause when the app goes to the background

## Controls

| Touch | Action |
|---|---|
| Drag left / right | Move |
| Drag down | Soft drop |
| Tap | Rotate clockwise |
| Flick down | Hard drop |
| Flick up | Hold |

On-screen buttons: hold, soft drop, left, right (auto-repeat), hard drop, rotate ↺ / ↻, pause.
Keyboard: arrows, `Z` / `X` rotate, `Space` hard drop, `C` hold, `P` pause.

## Install

Download `tetris-<version>.apk` from [Releases](https://github.com/ouaazizdriss/tetris/releases) and open it on your phone
(allow installing from unknown sources). Requires Android 8.0+.

## Build

```sh
./gradlew testDebugUnitTest   # game-logic unit tests
./gradlew installDebug        # install debug build on a connected device
./gradlew assembleRelease     # release APK (signed if keystore.properties exists)
```

Release signing reads `keystore.properties` at the project root (not committed):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Project layout

- `app/src/main/java/com/ouaaziz/tetris/TetrisGame.kt`: pure game rules (board, rotation, gravity, scoring)
- `app/src/main/java/com/ouaaziz/tetris/TetrisView.kt`: Canvas rendering and touch input
- `app/src/main/java/com/ouaaziz/tetris/MainActivity.kt`: activity and full-screen setup
