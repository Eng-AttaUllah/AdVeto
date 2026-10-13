# AdVeto — Architecture (proposal §7, Figure 1)

Seven stages. Each stage has a stated input, output, and failure behaviour.
`→` is the happy path; every other path terminates in an explicit, logged state.

```
┌──────────────────────────────────────────────────────────────────────────┐
│ (1) SCREEN CAPTURE — MediaProjection                                    │
│     frame (Bitmap)  ·  requires explicit consent dialog                  │
│     DENIED → state=no-monitoring, logged, pipeline does not start       │
└──────────────┬───────────────────────────────────────────────────────────┘
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ (2) AD-REGION DETECTION                                                  │
│     proposals: [Rect, source∈{accessibility, ocr-cluster, banner-geom}] │
│     no region localised → event outcome=not-processed (reason code)      │
│     NEVER counted as safe                                                │
└──────┬───────────────────────────────┬───────────────────────────────────┘
       ▼                               ▼
┌─────────────────────┐     ┌──────────────────────────────────┐
│ (3a) IMAGE MODEL    │     │ (3b) OCR → TEXT MODEL            │
│ region → p_img      │     │ ML Kit → string → p_txt          │
│ LiteRT, int8/float  │     │ OCR empty → flag image-only      │
└──────────┬──────────┘     └────────────────┬─────────────────┘
           └──────────────┬──────────────────┘
                          ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ (4) SCORE-LEVEL FUSION                                                   │
│     p = w_img·p_img + w_txt·p_txt      (w tuned on validation)           │
│     learned logistic fusion = ablation                                   │
│     max(p) < τ → ABSTAIN (logged uncertain, rate reported)               │
└──────────────┬───────────────────────────────────────────────────────────┘
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ (5) AGE-AWARE POLICY                                                     │
│     (age_band, category, confidence) → ALLOW | COVER | ABSTAIN           │
│     pure, side-effect free — testable as a table                         │
└──────────────┬───────────────────────────────────────────────────────────┘
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ (6) OVERLAY      COVER only → draw over the ad region (else no-op)       │
│     overlay fails to draw → logged as bypass case, not silently ignored  │
└──────────────┬───────────────────────────────────────────────────────────┘
               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ (7) LOCAL LOG + DASHBOARD (Room/SQLite)                                  │
│     event = {ts, package, category, confidence, outcome, reason, ms}     │
└──────────────────────────────────────────────────────────────────────────┘
```

## Module contracts (Kotlin)

| Module | Responsibility | State it can produce |
|---|---|---|
| `capture.ScreenCaptureService` | foreground service, MediaProjection, frame throttling | `authorised`, `denied`, `suspended` |
| `detect.AdRegionDetector` | region proposals + provenance | list of `Region(rect, source)` or `none` |
| `ml.ImageClassifier` | LiteRT image model | `p_img[4]`, latency |
| `ml.OcrEngine` | ML Kit text recognition | string or `empty` |
| `ml.TextClassifier` | LiteRT/ONNX text model | `p_txt[4]` or `unavailable` |
| `fusion.Fusion` | weighted / logistic combination | `p[4]` + `modality` |
| `policy.AgePolicy` | pure decision table | `ALLOW`/`COVER`/`ABSTAIN` |
| `overlay.OverlayController` | draw/clear the cover view | `drawn`/`failed` |
| `log.EventDao` + `log.EventLogger` | persist decisions | rows |
| `dashboard.*` | read-only reporting over `EventDao` | aggregates |

**Why these boundaries matter:** stages 1, 2, 6 depend on the platform and are
exactly what the Week 1–2 GO/NO-GO gate tests; stages 3–5 are the FYP's
model/policy contribution and are evaluated offline against the frozen test
split. Keeping them separate means a gate failure (e.g. cross-app overlay
restricted) shrinks the evaluated scope without rewriting the pipeline.

## Four first-class fallbacks (proposal §7)

| Condition | Behaviour | Logged as |
|---|---|---|
| fused confidence < τ | do not allow, do not cover | `abstain` (+ abstain rate reported) |
| creative not localised | nothing drawn | `not-processed`, reason code, excluded from detection denominators |
| OCR returns nothing | classify on image only | `image-only` flag |
| MediaProjection declined | no monitoring | `no-monitoring` state |

## Age bands

Defined in `docs/04-age-policy.md` (written before labelling): the policy maps
`(age_band × category × confidence)` → outcome, and the test cases in
deliverable 3 are generated from the held-out set against exactly this table.
