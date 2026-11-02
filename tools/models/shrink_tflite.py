#!/usr/bin/env python3
"""Stores a float TFLite model's large convolution weights in fewer bits, the way TFLite's own converter does for float16 quantisation: each weight
tensor becomes a smaller constant (float16, or int8 with a scale per output channel) followed by a DEQUANTIZE op that restores the float tensor the
convolution reads. Nothing else in the graph changes, so the network computes the same thing up to the rounding of its weights.

    shrink_tflite.py in.tflite out.tflite --to fp16|int8
    shrink_tflite.py --compare original.tflite shrunk.tflite image.jpg ...   (agreement of the two networks' outputs)

Why: the MiDaS v2.1 small depth network (isl-org/MiDaS, MIT) is 66 MB as float32, too large to ship in the camera app (the whole APK is 9.5 MB).
Needs ai-edge-litert and numpy (and pillow for --compare).
"""
import argparse, os, sys
import numpy as np
from ai_edge_litert import schema_py_generated as fb
from ai_edge_litert.tools import flatbuffer_utils as fu

WEIGHT_INPUT = {fb.BuiltinOperator.CONV_2D: (1, 0), fb.BuiltinOperator.DEPTHWISE_CONV_2D: (1, 3)}   # op -> (input slot of the weights, output-channel axis)


def opcode_index(model, builtin):
    for i, oc in enumerate(model.operatorCodes):
        if fu.get_builtin_code_from_operator_code(oc) == builtin: return i
    oc = fb.OperatorCodeT(); oc.builtinCode = builtin; oc.deprecatedBuiltinCode = min(builtin, 127); oc.version = 3
    model.operatorCodes.append(oc); return len(model.operatorCodes) - 1


def shrink(src, dst, to, min_elems=1024):
    model = fu.read_model(src)
    g = model.subgraphs[0]
    deq = opcode_index(model, fb.BuiltinOperator.DEQUANTIZE)
    targets = {}
    for op in g.operators:
        code = fu.get_builtin_code_from_operator_code(model.operatorCodes[op.opcodeIndex])
        if code in WEIGHT_INPUT:
            slot, axis = WEIGHT_INPUT[code]; t = op.inputs[slot]
            targets[t] = axis
    new_ops = []; saved = 0
    for t, axis in targets.items():
        tensor = g.tensors[t]; buf = model.buffers[tensor.buffer]
        if tensor.type != fb.TensorType.FLOAT32 or buf.data is None: continue
        w = np.frombuffer(bytes(buf.data), dtype='<f4').reshape(tensor.shape)
        if w.size < min_elems: continue
        q = fb.TensorT(); q.shape = list(tensor.shape); q.name = (tensor.name.decode() if isinstance(tensor.name, bytes) else tensor.name) + f'_{to}'
        nb = fb.BufferT()
        if to == 'fp16':
            q.type = fb.TensorType.FLOAT16; nb.data = np.frombuffer(w.astype('<f2').tobytes(), np.uint8)
        else:
            m = np.moveaxis(w, axis, 0).reshape(w.shape[axis], -1)
            scale = np.maximum(np.abs(m).max(1), 1e-12) / 127.0
            shp = [1] * w.ndim; shp[axis] = -1
            qi = np.clip(np.round(w / scale.reshape(shp)), -127, 127).astype(np.int8)
            q.type = fb.TensorType.INT8; nb.data = np.frombuffer(qi.tobytes(), np.uint8)
            qp = fb.QuantizationParametersT(); qp.scale = scale.astype(np.float32).tolist(); qp.zeroPoint = [0] * len(scale); qp.quantizedDimension = axis
            q.quantization = qp
        model.buffers.append(nb); q.buffer = len(model.buffers) - 1
        g.tensors.append(q); qi_idx = len(g.tensors) - 1
        saved += w.nbytes - len(nb.data)
        # the float tensor is now produced by the DEQUANTIZE op, not stored
        buf.data = None   # its old float data goes (the buffer belonged to this tensor alone)
        op = fb.OperatorT(); op.opcodeIndex = deq; op.inputs = [qi_idx]; op.outputs = [t]
        new_ops.append(op)
    g.operators = new_ops + g.operators   # every DEQUANTIZE comes before any op that reads its output
    fu.write_model(model, dst)
    print(f'{len(new_ops)} weight tensors as {to}: {os.path.getsize(src) / 1e6:.1f} MB -> {os.path.getsize(dst) / 1e6:.1f} MB')


def run(path, x):
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(path); it.allocate_tensors()
    i = it.get_input_details()[0]; o = it.get_output_details()[0]
    it.set_tensor(i['index'], x.astype(np.float32)); it.invoke(); return it.get_tensor(o['index']).squeeze()


def compare(a, b, images):
    from PIL import Image, ImageOps
    mean = np.array([.485, .456, .406]); std = np.array([.229, .224, .225])
    for p in images:
        im = ImageOps.exif_transpose(Image.open(p)).convert('RGB').resize((256, 256), Image.BILINEAR)
        x = ((np.asarray(im, np.float32) / 255 - mean) / std)[None]
        da = run(a, x); db = run(b, x)
        r = np.corrcoef(da.ravel(), db.ravel())[0, 1]
        rel = np.abs(da - db).mean() / (np.abs(da).mean() + 1e-9)
        # the nearest tenth of the picture by each network: how much they agree on what is in front
        na = da >= np.percentile(da, 90); nb = db >= np.percentile(db, 90)
        print(f'{os.path.basename(p):40s} correlation {r:.5f}  mean relative difference {rel * 100:.2f}%  nearest-10% overlap {(na & nb).sum() / (na | nb).sum():.3f}')


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('paths', nargs='+'); ap.add_argument('--to', choices=['fp16', 'int8'], default='fp16'); ap.add_argument('--compare', action='store_true')
    a = ap.parse_args()
    if a.compare: compare(a.paths[0], a.paths[1], a.paths[2:])
    else: shrink(a.paths[0], a.paths[1], a.to)


if __name__ == '__main__':
    main()
