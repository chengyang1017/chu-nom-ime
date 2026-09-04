# Chữ Nôm IME for Android

**English | [简体中文](README.zh-CN.md)**

A native Android input method for typing **Vietnamese and Chữ Nôm** with offline candidate lookup, Vietnamese Telex composition, sentence-level candidate generation, and dedicated Hán-Nôm font support.

The project is built in Kotlin on top of Android's `InputMethodService` API. It is designed as an actual keyboard/IME rather than a text-conversion demo: users can enable it from Android settings and type directly inside other applications.

---

## Screenshots

> Screenshot placeholders are intentionally kept here. Add images under `docs/screenshots/` when ready.

### Keyboard

📸 **Screenshot placeholder:** `docs/screenshots/keyboard.png`

### Vietnamese → Chữ Nôm Candidates

📸 **Screenshot placeholder:** `docs/screenshots/candidates.png`

### Sentence Input

📸 **Screenshot placeholder:** `docs/screenshots/sentence-input.png`

### Telex Input

📸 **Screenshot placeholder:** `docs/screenshots/telex.png`

### Settings

📸 **Screenshot placeholder:** `docs/screenshots/settings.png`

### Tablet Layout

📸 **Screenshot placeholder:** `docs/screenshots/tablet.png`

---

## What This Project Does

The goal is to make Chữ Nôm usable as an everyday input method instead of limiting it to dictionaries, static conversion pages, or copy-and-paste workflows.

The current Android application provides:

- A real Android IME service
- Vietnamese keyboard input
- Telex composition
- Offline Chữ Nôm candidate lookup
- Candidate ranking
- Sentence-level candidate generation
- Phrase segmentation support
- Vietnamese tone-restoration infrastructure
- Local database / in-memory indexing
- Dedicated Hán-Nôm fonts
- A launcher settings screen for enabling and selecting the keyboard

---

## Input Flow

At a high level, input moves through several layers:

```text
Keyboard input
      ↓
Vietnamese / Telex composition
      ↓
Input state
      ↓
Local Chữ Nôm engine
      ↓
Candidate generation
      ↓
Candidate ranking
      ↓
Sentence composition
      ↓
Android InputConnection
      ↓
Text committed into another app
```

This keeps keyboard UI, linguistic processing, candidate search, sentence composition, and Android text commitment separated from one another.

---

## Android IME Architecture

The keyboard is registered as a real Android input method service:

```text
NomInputMethodService
        ↓
KeyboardController
        ↓
NomInputState / SentenceCompositionState
        ↓
NomEngine / SentenceNomEngine
        ↓
InputConnectionController
        ↓
Android application text field
```

`NomInputMethodService` is declared with Android's `BIND_INPUT_METHOD` permission, allowing the app to appear in the system keyboard list after installation.

The application also exposes a settings activity that can guide the user to:

- Enable the keyboard
- Choose the active input method

---

## Vietnamese Input

### Telex Composition

The project contains a dedicated `TelexComposer` rather than treating Latin input as plain text.

This gives the IME a foundation for processing Vietnamese input before searching for Chữ Nôm candidates.

Relevant components include:

```text
TelexComposer.kt
VietnameseInputParser.kt
```

The design allows Vietnamese orthography handling to remain independent from the Chữ Nôm candidate engine.

---

## Chữ Nôm Candidate Engine

The local engine is split into several responsibilities:

```text
NomEngine
├── LocalNomEngine
├── NomCandidateRanker
├── local data repository
└── local search index
```

The project includes local data structures such as:

```text
NomCandidate
NomSearchEntry
NomSourceEntry
NomSentenceCandidate
```

and local data infrastructure including:

```text
NomCsvLoader
NomDatabase
NomMemoryIndex
Utf8CsvReader
```

The intent is to keep candidate lookup usable offline and avoid requiring a network request for every keystroke.

---

## Sentence-Level Processing

The project goes beyond isolated one-word lookup.

The sentence engine currently contains components for:

```text
SentenceNomEngine
SentenceCandidateGenerator
SentenceCandidateRanker
NomPhraseSegmenter
VietnameseToneRestorer
SentenceQueryContext
LatestQueryCoordinator
```

This architecture supports work toward sentence-aware input, where the IME can evaluate multiple candidate combinations rather than forcing the user to convert every syllable independently.

A simplified model is:

```text
Vietnamese sentence
       ↓
Phrase segmentation
       ↓
Candidate generation
       ↓
Candidate combinations
       ↓
Ranking
       ↓
Suggested Chữ Nôm sentence
```

---

## Offline Data

The keyboard bundles its Chữ Nôm data locally.

Current assets include:

```text
app/src/main/assets/
├── hannom_rcv_standard_nom.csv
├── hannom_rcv_metadata.json
├── fonts/
└── licenses/
```

The application settings describe the keyboard as operating offline with data from **Hội Bảo tồn Di sản chữ Nôm**.

A Python preprocessing tool is also included under:

```text
tools/extract_hannom_rcv.py
```

with a corresponding test script for the extraction pipeline.

---

## Font Support

Chữ Nôm contains characters that are not reliably covered by ordinary Android system fonts.

The repository therefore includes dedicated Hán-Nôm font assets, including:

```text
han_nom_primary.ttf
plangothic_p1.ttf
```

Font license files are stored alongside the project assets.

This is important for an input method because generating the correct Unicode character is not enough if the candidate UI cannot render it correctly.

---

## Project Structure

```text
chu-nom-ime/
├── app/
│   └── src/main/
│       ├── assets/
│       │   ├── fonts/
│       │   ├── licenses/
│       │   ├── hannom_rcv_metadata.json
│       │   └── hannom_rcv_standard_nom.csv
│       │
│       ├── java/com/example/chineseime/
│       │   ├── data/
│       │   ├── engine/
│       │   │   └── sentence/
│       │   ├── ime/
│       │   └── ui/
│       │
│       ├── res/
│       └── AndroidManifest.xml
│
├── tools/
│   ├── extract_hannom_rcv.py
│   └── test_extract_hannom_rcv.py
│
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Tech Stack

### Android

- Kotlin
- Android SDK
- `InputMethodService`
- AndroidX
- Material Components
- ConstraintLayout

### Language Processing

- Vietnamese Telex composition
- Vietnamese input parsing
- Candidate ranking
- Phrase segmentation
- Sentence candidate generation
- Tone-restoration infrastructure

### Local Data

- CSV-based source data
- Local database layer
- In-memory search index
- Offline bundled metadata

### Tooling

- Gradle Kotlin DSL
- JUnit
- Android instrumentation tests
- Python preprocessing scripts

---

## Build Requirements

The Android module currently targets:

```text
minSdk 24
targetSdk 36
Java 17
```

You can build from Android Studio or with Gradle.

### Windows

```powershell
.\gradlew.bat assembleDebug
```

### macOS / Linux

```bash
./gradlew assembleDebug
```

Install the resulting APK on an Android device, then enable the keyboard from the Android input-method settings.

---

## Testing

The repository contains both unit-test and Android instrumentation-test infrastructure.

There is also a sentence-engine device performance test under the Android test source set.

Typical local checks:

```bash
./gradlew test
./gradlew connectedAndroidTest
```

---

## Design Direction

This project is not intended to require the user to constantly switch between a separate Latin keyboard and a separate Chữ Nôm converter.

The longer-term direction is an input experience where Vietnamese input, Chữ Nôm candidates, phrase context, and sentence-level suggestions coexist inside the keyboard itself.

Conceptually:

```text
Vietnamese typing
      +
Chữ Nôm lookup
      +
Context
      +
Sentence ranking
      =
Practical Chữ Nôm input
```

---

## Status

**Active development.**

The repository already contains the native Android IME service, keyboard controller, local Chữ Nôm data layer, Telex/Vietnamese processing, sentence candidate engine, offline assets, font support, and settings flow.

Current work is focused on improving candidate quality, sentence-level behavior, input ergonomics, device compatibility, and making the keyboard practical for real-world Android use.
