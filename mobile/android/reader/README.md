# VoiceStudio Reader (Android)

A separate, focused Android app for listening to novels in Vietnamese: open an EPUB
and it reads aloud, on the phone, in a Vietnamese voice or in a voice cloned from a
short recording. Its engine is [VieNeu-TTS v3 Nano](https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Nano)
(Apache-2.0) on ONNX Runtime, with the [sea-g2p](https://github.com/pnnbao97/sea-g2p)
phonemiser the model was trained with. From VoiceStudio it reuses book import, audio
decoding and AAC export (`:core:local`) and the version; it has its own app id
(`sh.voicestudio.reader`), launcher icon and private storage.

```sh
# once: the phonemiser is Rust
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

cd mobile/android
./gradlew :reader:assembleDebug
./gradlew :reader:testDebugUnitTest
```

On Windows without Visual Studio, use the GNU host toolchain
(`rustup default stable-x86_64-pc-windows-gnu`), which brings its own linker.

## What it does

- **Library.** Import EPUB (also PDF and plain text). Chapters come from the EPUB
  spine; the title and author come from its package document. The listening
  position is saved per book.
- **Voice model.** Settings downloads VieNeu-TTS v3 Nano once (about 350 MB with the
  phonemiser dictionary and the preset voices). Every file is pinned to one upstream
  revision in `src/main/assets/vieneu-release.json` and checked against its SHA-256;
  a file that differs is discarded. After the download the app reads offline.
- **Voices.** The model's own Vietnamese voices (northern and southern, male and
  female), or **Clone a voice**: pick a short, clean recording of one person speaking
  (5 to 10 seconds is best, at least 2; only the first 30 are heard) and confirm you
  may use the voice. A VoiceStudio export (`.ovsvoice`, or the older `.omnivoice`)
  works too; its reference clip is used. Cloning downloads three more graphs (about
  95 MB) the first time. The app does not denoise the recording, so record somewhere
  quiet. A cloned voice is stored as numbers the model speaks with, plus the clip.
- **Fluent long reading.** Text is split into pieces of a few sentences (never on
  abbreviations or decimals). Pieces are generated ahead of the listening position
  (40 by default, adjustable), loudness-matched, trimmed and joined with
  sentence, paragraph and chapter pauses, then cached as AAC. The model reads at most
  about fifteen seconds in one go, so a long piece is spoken in parts of up to 140
  characters. **Prepare whole book** generates everything, for example overnight
  while charging. **Faster generation** halves the model's steps: about twice as
  fast, slightly rougher. A piece the model cannot read is skipped with a short
  silence instead of stopping the book.
- **Vietnamese text.** Unicode is composed (NFC) so tone marks stored as combining
  characters read correctly. Numbers follow spoken rules (mốt, lăm, tư, linh/lẻ;
  nghìn or ngàn by region), with Vietnamese grouping (1.000.000) and decimal commas.
  Dates, clock times (7h30), money (đ, VNĐ, $), percentages, units, ranges,
  ordinals (thứ 4 → thứ tư), Roman numerals after chapter words (Chương IV) and
  common abbreviations (TP.HCM, v.v., GS.TS.) become words. Footnote markers,
  dialogue dashes and scene-break lines are dropped. A pronunciation list in
  Settings (`written = spoken`) fixes character names and foreign words. sea-g2p
  then turns the spoken-form text into the phonemes the model reads.
- **Player.** 0.5×–2× speed without pitch change, a looping background-music file
  with its own volume (it follows the narration and never takes audio focus), a
  sleep timer (minutes or end of chapter) that fades out, and lock-screen,
  headset and Bluetooth controls through a media session.

Books, voices and generated audio stay in app-private storage. The only network use
is the model download from Hugging Face and GitHub (`huggingface.co`,
`github.com`, `raw.githubusercontent.com`) that the user starts.

## Code map

- `text/`: `VietnameseText` and `VietnameseNumbers` (normalization), `Chunker`
  (pieces and pauses), `BookText` (chapters and EPUB metadata). Pure Kotlin.
- `vieneu/`: the engine, plain JVM code. `NanoEngine` is a port of the upstream
  sampling loop (`vieneu/v3nano.py`); `NanoEnroller` and `SpeechFeatures` (Kaldi
  filterbank, resampling) enrol a recording; `SeaG2p` is the JNI wrapper;
  `NanoRelease` and `NanoAssets` are the pinned manifest and its downloader.
- `native/g2p/`: the Rust JNI bridge to sea-g2p, pinned to the v0.10.0 commit. The
  dictionary downloaded by the app must come from the same release.
- `voice/`: `ReaderVoices` stores cloned voices; `VoiceBundle` reads desktop exports.
- `playback/`: `VieNeu` (loads and runs the model), `Renderer` (piece → normalized,
  polished, cached audio), `Narration` (player queue, render-ahead loop, music,
  sleep timer), `ReaderService` (foreground service, media session, wake lock),
  `AudioPolish`.
- `ui/`: Compose screens for the library, reader, voices and settings, in English
  and Vietnamese (`res/values-vi`).

## Tests

The engine is tested off the phone against stand-in graphs with the real input and
output names (`scripts/make_nano_fixture.py`), so the sampling loop and enrolment
are checked exactly without the weights. The filterbank is compared with
kaldi-native-fbank (`scripts/make_fbank_fixture.py`). `SeaG2pTest` runs when a host
build of the bridge and the dictionary are provided (see the test).

The rendered-audio cache key includes `VietnameseText.VERSION` and the release id;
bump the version whenever a normalization rule changes what is spoken, so stale
audio is regenerated.
