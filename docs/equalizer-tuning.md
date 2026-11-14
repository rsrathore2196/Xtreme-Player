# Equalizer tuning

The existing five bands remain 60, 230, 910, 3,600 and 14,000 Hz. The 20 existing preset names and all manual controls remain available. Curves are authored tonal starting points, not headphone correction profiles or a claim that one sound suits every recording/device.

| Preset | 60 Hz | 230 Hz | 910 Hz | 3.6 kHz | 14 kHz |
| --- | ---: | ---: | ---: | ---: | ---: |
| Flat | 0 | 0 | 0 | 0 | 0 |
| Crystal Clarity | 0 | -1.5 | 0 | 1.5 | 0.5 |
| Studio Master | 0 | -0.5 | 0 | 0.5 | 0.5 |
| Bass Booster | 4 | 1.5 | -0.5 | 0 | 0 |
| Deep Sub | 4.5 | -0.5 | -0.5 | 0 | 0 |
| Bass Reducer | -4 | -2 | 0 | 0 | 0 |
| Electronic / EDM | 3 | 0.5 | -1 | 1 | 1.5 |
| Rock & Metal | 2 | 0.5 | -0.5 | 1.5 | 1 |
| Hip-Hop & R&B | 3.5 | 1 | -0.5 | 0.5 | 0.5 |
| Dance & Club | 3 | 1 | -0.5 | 1 | 1.5 |
| Pop Commercial | 1 | 0.5 | 0 | 1 | 1 |
| Vocal & Podcast | -2.5 | -1 | 1 | 2 | -0.5 |
| Acoustic Live | 0.5 | -0.5 | 0.5 | 1 | 0.5 |
| Classical Concert | 0.5 | 0 | 0 | 0.5 | 1 |
| Jazz Lounge | 1 | 0.5 | 0.5 | 0.5 | 0.5 |
| Treble Booster | 0 | 0 | 0 | 1.5 | 3 |
| Treble Reducer | 0 | 0 | 0 | -1.5 | -3 |
| Dynamic Cinema | 2 | -0.5 | 0.5 | 1.5 | 0.5 |
| Car Audio (Cabin Dynamics) | -1.5 | -2 | 0 | 1 | 0.5 |
| Gaming & Spatial | -1.5 | -1 | 0.5 | 2 | 0.5 |

Values are dB. Presets do not add auxiliary bass boost or loudness gain. Crystal Clarity retains its separate 0.8 dB bass peak and 1.5 dB air shelf; its primary curve accounts for those stages. Existing modest cinema/gaming width remains available and yields to the independently selected stereo spatializer.

Filters use the Audio EQ Cookbook peaking/shelving equations (Q 0.8 for the five peak bands), with double-precision coefficients and history. Frequencies beyond Nyquist are bypassed rather than moved into lower bands. Custom/restored center frequencies receive logarithmic interpolation of the preset anchors.

Headroom is based on the combined cascade response at 256 logarithmically spaced frequencies plus DC/Nyquist, including clarity stages. A 0.25 dB margin covers grid spacing for these broad filters. Stereo width contributes its own gain allowance. This avoids summing unrelated bass/treble peaks and unnecessarily quieting the whole signal. Attenuation uses a 1 ms attack ahead of the slower filter morph; gain recovery waits for the 10 ms filter transition, then releases over 20 ms. This avoids an instantaneous preamp volume step. The existing soft limiter remains the final safety stage, particularly for manually requested positive loudness gain and independently enabled spatial audio. This is not a true-peak mastering limiter or a loudness-normalization system.

EQ off, and neutral Flat with other DSP off, copy PCM byte-for-byte. Switching off clears filter history even when the sink skips processing. Independently enabled spatial audio, skip silence, volume, crossfade, device effects and output conversion keep their own behavior. EQ cannot undo source compression, Android mixing or external/device processing.

EQ enabled must leave native direct USB output after draining accepted audio; it runs before either selected shared PCM output precision. Encoded Atmos is decoded before PCM EQ; multichannel PCM retains its channel order and never receives stereo-only widening. These checks do not certify proprietary Atmos rendering on a particular phone/receiver. Old factory presets migrate on restore; custom settings and the saved power/clarity choices are retained.

## Sources

- [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/): biquad equations, gain, frequency and Q/bandwidth definitions.
- [Equalizer APO configuration reference](https://sourceforge.net/p/equalizerapo/wiki/Configuration%20reference/): preamp attenuation to avoid clipping from positive-gain filters.
- [FabFilter Pro-Q output documentation](https://www.fabfilter.com/help/pro-q/using/output): output gain compensation and downstream clipping considerations.
- [AutoEq measurement-based results](https://github.com/jaakkopasanen/AutoEq/tree/master/results): device-specific correction depends on actual headphone measurements and target response. No profiles were copied.
- [Android Spatializer](https://developer.android.com/reference/android/media/Spatializer): spatialization support depends on device capabilities.

The sources support the engineering principles; the exact genre vectors above are our own tuning choices. Hardware listening and external Dolby/device-DSP behavior require physical route validation.
