"""Writes the tiny stand-in model used by NanoEngineTest.

The graphs have the real VieNeu-TTS v3 Nano input and output names but trivial maths
(the vector field is -x), so the test can check the sampling loop exactly without the
280 MB weights. Run: python scripts/make_nano_fixture.py src/test/resources/nano
"""
import json
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto as T, helper as h, numpy_helper as nh

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
CHANNELS, SPEAKER, TOKENS, WIDTH = 6, 4, 2, 3


def save(name, nodes, inputs, outputs, constants=()):
    graph = h.make_graph(nodes, name, inputs, outputs, list(constants))
    model = h.make_model(graph, opset_imports=[h.make_opsetid("", 17)])
    model.ir_version = 9
    onnx.checker.check_model(model)
    onnx.save(model, out / f"{name}.onnx")


def value(name, kind, shape):
    return h.make_tensor_value_info(name, kind, shape)


axis = nh.from_array(np.array([2], dtype=np.int64), "axis")
save("text_encoder",
     [h.make_node("Cast", ["ids"], ["f"], to=T.FLOAT), h.make_node("Unsqueeze", ["f", "axis"], ["ctx"])],
     [value("ids", T.INT64, [1, "L"]), value("style", T.FLOAT, [1, TOKENS, WIDTH])],
     [value("ctx", T.FLOAT, [1, "L", 1])], [axis])
# log(0.64 s): ten latent frames at 15.625 frames per second.
save("duration_predictor",
     [h.make_node("Constant", [], ["log_seconds"], value=nh.from_array(np.array([np.log(0.64)], dtype=np.float32)))],
     [value("ctx", T.FLOAT, [1, "L", 1]), value("ctx_mask", T.BOOL, [1, "L"]), value("spk", T.FLOAT, [1, SPEAKER])],
     [value("log_seconds", T.FLOAT, [1])])
save("vector_estimator",
     [h.make_node("Neg", ["x"], ["v"])],
     [value("x", T.FLOAT, [1, CHANNELS, "T"]), value("t", T.FLOAT, [1]), value("ctx", T.FLOAT, [1, "L", 1]),
      value("ctx_mask", T.BOOL, [1, "L"]), value("spk", T.FLOAT, [1, SPEAKER]), value("style", T.FLOAT, [1, TOKENS, WIDTH])],
     [value("v", T.FLOAT, [1, CHANNELS, "T"])])
shape = nh.from_array(np.array([1, 1, -1], dtype=np.int64), "shape")
save("codec_decoder",
     [h.make_node("Reshape", ["x", "shape"], ["wav"])],
     [value("x", T.FLOAT, [1, CHANNELS, "T"])], [value("wav", T.FLOAT, [1, 1, "N"])], [shape])

# Enrolment stand-ins: the speaker vector is the mean of the first four feature bins, the codec latent is the
# audio folded into two channels, and each style value is the time-mean of one grouped channel.
four = nh.from_array(np.array([0], np.int64), "s0"), nh.from_array(np.array([SPEAKER], np.int64), "s1"), nh.from_array(np.array([1], np.int64), "s2")
save("speaker_encoder",
     [h.make_node("ReduceMean", ["input"], ["m"], axes=[1], keepdims=0), h.make_node("Slice", ["m", "s0", "s1", "s2"], ["output"])],
     [value("input", T.FLOAT, ["B", "T", 80])], [value("output", T.FLOAT, ["B", SPEAKER])], four)
save("codec_encoder",
     [h.make_node("Reshape", ["wav", "fold"], ["mu"])],
     [value("wav", T.FLOAT, [1, 1, "N"])], [value("mu", T.FLOAT, [1, 2, "F"])],
     [nh.from_array(np.array([1, 2, -1], np.int64), "fold")])
save("reference_encoder",
     [h.make_node("ReduceMean", ["ref"], ["m"], axes=[2], keepdims=0), h.make_node("Slice", ["m", "s0", "s1", "s2"], ["six"]),
      h.make_node("Reshape", ["six", "grid"], ["style"])],
     [value("ref", T.FLOAT, [1, 12, "Tr"]), value("ref_mask", T.BOOL, [1, "Tr"])], [value("style", T.FLOAT, [1, TOKENS, WIDTH])],
     [nh.from_array(np.array([0], np.int64), "s0"), nh.from_array(np.array([6], np.int64), "s1"), nh.from_array(np.array([1], np.int64), "s2"),
      nh.from_array(np.array([1, TOKENS, WIDTH], np.int64), "grid")])

np.savez(out / "constants.npz", null_spk=np.zeros(SPEAKER, np.float32), null_style=np.zeros((TOKENS, WIDTH), np.float32),
         latent_mean=np.array([0.1, 0.0], np.float64), latent_std=np.array([2.0, 1.0], np.float32), latent_scale=np.float32(0.5))
(out / "config.json").write_text(json.dumps({
    "vocab": {"<pad>": 0, "<bos>": 1, "<eos>": 2, "a": 3, "b": 4, " ": 5, ".": 6, "①": 7}, "bos_id": 1, "eos_id": 2, "pad_id": 0,
    "group": 6, "ref_max_frames": 140, "flow_fps": 15.625, "emotion_tags": {"<|emotion_1|>": "①"},
}, ensure_ascii=True), encoding="utf-8")
