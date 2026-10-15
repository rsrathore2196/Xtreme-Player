# Backdrop renderer provenance

The source under app/src/main/java/com/example/ui/components/backdrop is adapted from BitChord commit fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f:
https://github.com/kushagrasinghx/BitChord/tree/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/app/src/main/java/com/music/bitchord/ui/components/backdrop

The original renderer is Kyant0/backdrop v2.0.0, Copyright 2025 Kyant0, Apache License 2.0.
Original project: https://github.com/Kyant0/backdrop

BitChord's repository and its adaptations are distributed under GPL-3.0. Both license texts are included here. Existing source attribution is retained.

Local adaptations rename the package, remove optional shader-editor annotations, and omit GraphicsLayerScope blendMode/colorFilter overrides unavailable in this app's Compose 1.7 API. Those overrides do not participate in the transform inversion. Xtreme's own integration retains its existing colours, shapes, rim, shadows, and foreground content while using the reduced-resolution effect pipeline and one-pass source recording.
