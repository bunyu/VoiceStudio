"""Writes the reference features SpeechFeaturesTest compares against.

Uses kaldi-native-fbank with exactly the options of the upstream speaker front end
(vieneu/_v3_turbo_engine/speaker/audio_utils.py). Run:
  pip install kaldi-native-fbank numpy && python scripts/make_fbank_fixture.py src/test/resources/fbank
"""
import sys
from pathlib import Path

import kaldi_native_fbank as knf
import numpy as np

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
rate = 16000
t = np.arange(int(rate * 0.6)) / rate
rng = np.random.default_rng(3)
# A voiced-like tone stack with vibrato plus a little noise, well inside full scale.
wave = (0.3 * np.sin(2 * np.pi * (140 + 20 * np.sin(2 * np.pi * 3 * t)) * t)
        + 0.15 * np.sin(2 * np.pi * 950 * t) + 0.05 * np.sin(2 * np.pi * 3400 * t)
        + 0.01 * rng.standard_normal(t.size)).astype(np.float32)
opts = knf.FbankOptions()
opts.frame_opts.samp_freq = rate
opts.frame_opts.frame_shift_ms = 10.0
opts.frame_opts.frame_length_ms = 25.0
opts.frame_opts.dither = 0.0
opts.frame_opts.snip_edges = True
opts.frame_opts.window_type = "povey"
opts.frame_opts.remove_dc_offset = True
opts.frame_opts.preemph_coeff = 0.97
opts.mel_opts.num_bins = 80
fb = knf.OnlineFbank(opts)
fb.accept_waveform(rate, wave.tolist())
fb.input_finished()
features = np.stack([fb.get_frame(i) for i in range(fb.num_frames_ready)]).astype(np.float32)
np.savez(out / "reference.npz", wave=wave, features=features)
print(features.shape)
