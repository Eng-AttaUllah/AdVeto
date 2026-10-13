# AdVeto — Ad-Region Detection and Age-Aware Risk Classification for Android Advertisements

**BS Computer Science — Final Year Project**
Sohaib Malik · Atta Ullah · Aqsa Brekhana · Supervisor: Mrs. Sawera Qureshi

Target: Android 10–15 (API 29–35). Research prototype, sideloaded.

## Research question

How close can an int8-quantised, on-device multimodal classifier come to its
floating-point counterpart on unsafe-advertisement recall and macro-F1, while
meeting a defined decision-latency budget on a mid-range phone?

## Pipeline (seven stages)

```
(1) MediaProjection capture
      → (2) ad-region detection
      → (3a) image model  ┐
      → (3b) ML Kit OCR → text model ┘
      → (4) score-level fusion
      → (5) age-aware policy (allow | cover | abstain)
      → (6) overlay (only on `cover`)
      → (7) local log + parent dashboard
```

Fallbacks are first-class states: low confidence → `abstain`;
uncapturable creative → `not-processed` (never counted as safe);
OCR failure → image-only; capture denial → logged no-monitoring.

## Repository layout

| Path | Contents |
|---|---|
| `docs/` | Requirements, category rubric, age-policy matrix, annotation guide, architecture |
| `data/` | Raw/processed/split image data (not committed — see `.gitignore`), split manifests committed |
| `scripts/` | Collection, provenance recording, dedupe, split building |
| `labeling/` | Dual-rater labelling tool + Cohen's κ / adjudication |
| `ml/` | Image / text / fusion training, evaluation, int8 quantisation, reports |
| `android/` | Kotlin prototype (Gradle project) |
| `models/` | Exported artifacts (float + int8) with a model card |

## Environment

- **Python** `.venv/` — Python 3.12 + TensorFlow/Keras (training, TFLite export).
  Create with `uv venv --python 3.12 .venv`, activate with `source .venv/bin/activate`.
- **Android** `~/opt/android-sdk` — cmdline-tools, platform, build-tools; JDK at `~/opt/jdk-17`.
  `android/local.properties` points at the SDK (generated, not committed).

## Status

- [x] Repository scaffold
- [ ] Toolchain: Python/TensorFlow venv, JDK, Android SDK, Gradle
- [ ] Batch 2 — Week 1–2 feasibility pilot (capture, region detection, overlay) → GO/NO-GO
- [ ] Batch 3 — Rubric + 3,000-region dual-rater dataset
- [ ] Batch 4 — Image / text / fusion benchmark + int8 quantisation
- [ ] Batch 5 — Integration, held-out evaluation, six deliverables
