# BitChord audio processing

`StereoSpatializer.kt` adapts the stereo widening and delayed crossfeed algorithm
from BitChord's `SpatialAudioProcessor.kt`, by BitChord contributors, licensed
under the GNU General Public License version 3. The license is reproduced in
`LICENSE-GPL-3.0.txt`.

Source: https://github.com/kushagrasinghx/BitChord
Revision: `edc1e6f36febf71a118e649411d2fc21b70ead45`
File: `app/src/main/java/com/music/bitchord/playback/SpatialAudioProcessor.kt`

The adaptation runs at Xtreme Player's existing PCM boundary, supports its
integer/float paths, and ramps the wet/dry mix when toggled. The original 2.5x
mid/side width, 0.82 output gain, 15 ms delay, 0.2 crossfeed, and 0.3 low-pass
coefficient are retained. Xtreme's existing output limiter remains in use.

Skip Silence uses AndroidX Media3's existing Apache-2.0-licensed
`SilenceSkippingAudioProcessor`, configured with BitChord's one-second minimum
silence duration and Media3's default retention parameters. No additional
third-party binary dependency is introduced.
