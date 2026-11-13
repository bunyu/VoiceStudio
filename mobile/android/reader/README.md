# VoiceStudio Reader (Android)

A separate, focused Android app for listening to novels: open an EPUB and it reads
aloud, on the phone, in a voice you made in VoiceStudio. It is tuned for Vietnamese.
It shares the main app's on-device engines (`:core:local`) and version, but has its
own app id (`sh.voicestudio.reader`), launcher icon and private storage.

```sh
cd mobile/android
./gradlew :reader:assembleDebug        # app/build is reused for the native runtimes
./gradlew :reader:testDebugUnitTest    # text, chunking, audio and voice-import tests
```

## What it does

- **Library.** Import EPUB (also PDF and plain text). Chapters come from the EPUB
  spine; the title and author come from its package document. The listening
  position is saved per book.
- **Your voice.** In VoiceStudio desktop, edit a voice and choose **Export persona**.
  Open the `.ovsvoice` file on the phone (older `.omnivoice` exports work too). The
  reader shows the voice's name and transcript for review; the transcript must match
  the sample word for word. A locked voice uses its locked take. Samples longer than
  30 seconds are refused, because the on-device model accepts nothing longer.
- **Engines.** OmniVoice (`omnivoice-q4-k-m`) clones the imported voice. It is the only
  on-device engine that both clones and reads Vietnamese (the model is trained on 600+
  languages; the catalogue lists only the languages the main app has verified). It is
  a 1.4 GB download, wants 8 GB of RAM and is usually slower than real time on a phone.
  Supertonic 3 (`supertonic3-int8`) reads Vietnamese quickly with ten preset voices
  but cannot clone. Each model's licence is shown and accepted before download.
- **Fluent long reading.** Text is split into pieces of a few sentences (never on
  abbreviations or decimals). Pieces are generated ahead of the listening position
  (40 by default, adjustable), loudness-matched, trimmed and joined with
  sentence, paragraph and chapter pauses, then cached as AAC. **Prepare whole book**
  generates everything, for example overnight while charging. A piece the model
  cannot read is skipped with a short silence instead of stopping the book.
- **Vietnamese text.** Unicode is composed (NFC) so tone marks stored as combining
  characters read correctly. Numbers follow spoken rules (mốt, lăm, tư, linh/lẻ;
  nghìn or ngàn by region), with Vietnamese grouping (1.000.000) and decimal commas.
  Dates, clock times (7h30), money (đ, VNĐ, $), percentages, units, ranges,
  ordinals (thứ 4 → thứ tư), Roman numerals after chapter words (Chương IV) and
  common abbreviations (TP.HCM, v.v., GS.TS.) become words. Footnote markers,
  dialogue dashes and scene-break lines are dropped. A pronunciation list in
  Settings (`written = spoken`) fixes character names and foreign words.
- **Player.** 0.5×–2× speed without pitch change, a looping background-music file
  with its own volume (it follows the narration and never takes audio focus), a
  sleep timer (minutes or end of chapter) that fades out, and lock-screen,
  headset and Bluetooth controls through a media session.

Books, voices and generated audio stay in app-private storage; the only network
use is the model downloads the user starts.

## Code map

- `text/`: `VietnameseText` and `VietnameseNumbers` (normalization), `Chunker`
  (pieces and pauses), `BookText` (chapters and EPUB metadata). Pure Kotlin.
- `voice/`: `VoiceBundle` reads desktop exports; `ReaderVoices` stores 24 kHz PCM
  references and previews.
- `playback/`: `Renderer` (piece → normalized, polished, cached audio), `Narration`
  (player queue, render-ahead loop, music, sleep timer), `ReaderService`
  (foreground service, media session, wake lock), `AudioPolish`, `Engines`.
- `ui/`: Compose screens for the library, reader, voices and settings, in English
  and Vietnamese (`res/values-vi`).

The rendered-audio cache key includes `VietnameseText.VERSION`; bump it whenever a
normalization rule changes what is spoken, so stale audio is regenerated.
