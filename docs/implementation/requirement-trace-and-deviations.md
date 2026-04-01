# Requirement Trace and Deviations

This document tracks implementation status, requirement deviations, impact scope, and follow-up plans.
Note: Code symbols and paths are intentionally kept here (not in `docs/requirements/requirements.md`).

## 1. Requirement Status Matrix

| Requirement Area | Status | Notes |
| --- | --- | --- |
| Victory condition judgment | Done | Implemented with complete property set counting. |
| Pause/resume in local and multiplayer flows | Done | Includes multiplayer confirmation flow. |
| Manual save/load | Done | JSON snapshot export/import supported. |
| Periodic autosave | Done | Round-based autosave path and feature flag present. |
| Performance smoke validation | Partial | Smoke thresholds exist; full load benchmarking still TODO. |
| Hand privacy in multiplayer broadcast | Done | Public snapshot hides private hand card details. |
| Local save-file protection | Done | Optional encrypted save storage with compatibility fallback. |

## 2. Deviations from Requirements

### 2.1 Victory Rule Wording Gap

- Requirement wording in `requirements.md` can be interpreted as "three complete sets of the same color".
- Current implementation applies "three complete sets in total (cross-color allowed)" which aligns with common Monopoly Deal rule interpretation.

Reason:

- To match mainstream card-rule interpretation and expected player behavior.

Impact:

- Rule explanation and QA expectations must follow one consistent interpretation.

Follow-up:

- TODO: Confirm whether course acceptance requires strict same-color interpretation.
- If strict same-color is required, update win-condition logic and regression tests together.

## 3. Traceability to Implementation Areas

### 3.1 Core Rule and Session Flow

- Win condition and property-set counting: game control and property-set calculation modules.
- Pause/resume and round advancement: controller and turn manager modules.

### 3.2 Persistence

- Manual export/import snapshots: session memento capture/restore pipeline.
- Autosave trigger: round completion checks and save feature flag.
- Encrypted save storage: save encryption codec with plaintext compatibility fallback.

### 3.3 Protocol and Privacy

- Public state broadcasting hides hand details (`handCount` only).
- Private hand details are delivered to bound player sessions only.

## 4. Risks and Planned Actions

- **Rule interpretation risk:** Requirement phrasing vs implemented gameplay may diverge in grading/demo expectations.
- **Performance evidence gap:** Smoke tests are not full stress tests.
- **Protocol drift risk:** Message type changes can desynchronize docs and implementation.

Planned actions:

- Keep this trace doc updated whenever requirement interpretation or protocol behavior changes.
- Add TODO-annotated acceptance checkpoints before release/demo.
