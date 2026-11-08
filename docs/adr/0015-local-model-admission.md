# ADR 0015: Admit an on-device model only after it beats the deterministic baseline

Status: **Accepted by owner, 2026-11-08** · Implementation pending

Builds on Accepted ADR 0014 and D-G1…D-G8. Acceptance is for this admission policy, not approval to add any particular model/runtime or a paid/network dependency.

## Context

MediaPipe, LiteRT and ONNX Runtime support local image inference, but have different native/runtime footprints. Model weights have their own licenses. KeepClear has a 50 MB app-data budget, a 200 MB scan-memory target, API 30 support, reproducible FOSS builds and no-GMS/no-tracking principles. A nominally free model can fail those constraints.

The official MobileNetV3-small float32 v1 artifact inspected in this research is 4,117,670 bytes, outputs 1024 floats and carries Apache-2.0 license metadata. Its usefulness on KeepClear images has not been tested. Larger semantic encoders may provide different capabilities, but their cost cannot be assumed acceptable.

## Decision

1. Compare the hash-only baseline against one concrete bundled MobileNetV3-small candidate only if justified. No runtime is selected: examined LiteRT 2.x packages include a denied Play-delivery dependency and examined MediaPipe core packages include data-transport components. Evaluate the documented LiteRT 1.4.2 CPU compatibility line only after maintenance/native review, or ONNX Runtime with a validated model conversion/encoder. Do not blindly exclude dependencies or assume an old version solves privacy.
2. Learned inference is allowed only on version-valid ambiguous participants emitted by the deterministic router, under GAL-24/25 run-wide budgets, cache reuse, lazy initialization and early stopping. No whole-library embedding pass. Ship **one** admitted runtime, not a speculative multi-runtime framework. Keep the pure-JVM engine independent of runtime/model details and keep a fully functional deterministic path.
3. Require verified origin, exact digest, redistribution license/NOTICE, source/conversion provenance, declared input/output/preprocessing and pinned dependencies. Review exact runtime/transitive modules and native artifacts before allowlisting them. Check F-Droid's build/dependency policy for the actual native/model artifacts; Apache-2.0 alone does not guarantee distribution acceptance. Do not allowlist a broad Google/Microsoft group or accept `latest.release`.
4. Use bundled local assets. No model downloader, Play Services runtime, telemetry, acceleration service or cloud fallback. Update models through a reviewed app release with recalibration/version changes.
5. Require AC-G11's recall and review-time gains, AC-G4 precision, all safety tests and resource/offline/native gates. Byte cache quantization, model quantization and GPU/NPU acceleration each need separate evaluation.
6. Preserve total app-data ≤50 MB and existing scan-memory <200 MB; gallery cannot expand these silently. The proposed gallery cache maximum is 16 MB within the whole-app budget, not in addition to it. Measure package/native overhead too.
7. If a gate fails, keep the deterministic implementation, narrow the supported task, or defer the feature. Do not use cloud as an automatic recovery path and do not lower safety/precision thresholds to make AI appear useful.

## Consequences

Model integration remains optional and reversible. Vendor benchmark figures guide experiments but do not establish acceptance. A new model/runtime adds supply-chain, 16 KiB native compatibility, ABI/R8, device and lifecycle testing work. Fixed assets simplify clean-install offline behavior and reproducibility but increase installed package size.

## Alternatives considered

- Select a model from published accuracy/latency alone: not representative of the personal-photo review task.
- Download a large model on demand: adds networking, setup and low-storage failure modes.
- Ship CLIP/SigLIP2 and a text model immediately: not justified for conservative photo grouping.
- Assume Apple MobileCLIP software license covers weights: the inspected weights have research-only restrictions.

## Required implementation follow-up

The records are indexed as Accepted. Only if an actual model/runtime passes admission, add its exact notices to THIRD_PARTY.md and in-app licenses; update the version catalog, dependency verification, manifest/dependency/bytecode confinement tests and privacy/help text in the same implementation milestone. ADR 0008/0011 networking is not relaxed by this record.
