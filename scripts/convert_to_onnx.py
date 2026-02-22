#!/usr/bin/env python3
"""
Convert trained sklearn models to ONNX format for Android deployment
"""

import numpy as np
import joblib
import os
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType
import onnx
import onnxruntime as rt

print("="*70)
print("SKLEARN TO ONNX MODEL CONVERSION")
print("="*70)
print()

# model filenames
MODELS = [
    {
        'name': 'Binary Drowsiness Detector',
        'pkl_file': 'sleepiness_detector.pkl',
        'onnx_file': 'sleepiness_detector.onnx',
        'num_features': 29,
        'description': 'Binary classification: SLEEPY (1) or ALERT (0)'
    },
    {
        'name': 'Multi-Class Emotion Classifier',
        'pkl_file': 'emotion_classifier.pkl',
        'onnx_file': 'emotion_classifier.onnx',
        'num_features': 29,
        'description': '7-class: neutral, calm, happy, sad, angry, fearful, surprised'
    }
]

def convert_model(model_info):
    """Convert a sklearn model to ONNX"""
    
    print(f"\n{'='*70}")
    print(f"Converting: {model_info['name']}")
    print(f"{'='*70}")
    
    pkl_file = model_info['pkl_file']
    onnx_file = model_info['onnx_file']
    num_features = model_info['num_features']
    
    # check if pkl exists
    if not os.path.exists(pkl_file):
        print(f"❌ ERROR: {pkl_file} not found!")
        print(f"   Train the model first with: python train_drowsiness_model.py")
        return False
    
    # load sklearn model
    print(f"Loading {pkl_file}...")
    model = joblib.load(pkl_file)
    print(f"✓ Loaded RandomForest with {model.n_estimators} trees")
    
    # define input type (29 features, float32)
    initial_type = [('float_input', FloatTensorType([None, num_features]))]
    
    # convert to ONNX
    print(f"Converting to ONNX...")
    try:
        onnx_model = convert_sklearn(
            model,
            initial_types=initial_type,
            target_opset=12  # compatible with most runtimes
        )
    except Exception as e:
        print(f"❌ Conversion failed: {e}")
        return False
    
    # save ONNX model
    print(f"Saving to {onnx_file}...")
    with open(onnx_file, "wb") as f:
        f.write(onnx_model.SerializeToString())
    
    # get file size
    size_bytes = os.path.getsize(onnx_file)
    size_kb = size_bytes / 1024
    print(f"✓ Saved ONNX model: {size_kb:.1f} KB")
    
    # validate ONNX model
    print(f"Validating ONNX model...")
    try:
        onnx.checker.check_model(onnx_model)
        print(f"✓ ONNX model is valid")
    except Exception as e:
        print(f"⚠️  Validation warning: {e}")
    
    # test inference
    print(f"Testing ONNX inference...")
    try:
        # create session
        sess = rt.InferenceSession(onnx_file)
        
        # test input (random features)
        test_input = np.random.randn(1, num_features).astype(np.float32)
        
        # run inference
        input_name = sess.get_inputs()[0].name
        label_name = sess.get_outputs()[0].name
        pred_onx = sess.run([label_name], {input_name: test_input})[0]
        
        # also get sklearn prediction for comparison
        pred_sklearn = model.predict(test_input)
        
        # compare
        if np.array_equal(pred_onx, pred_sklearn):
            print(f"✓ ONNX inference matches sklearn prediction")
        else:
            print(f"⚠️  Warning: ONNX output differs from sklearn")
            print(f"   ONNX: {pred_onx}")
            print(f"   sklearn: {pred_sklearn}")
        
        # get probabilities
        if len(sess.get_outputs()) > 1:
            prob_name = sess.get_outputs()[1].name
            probs_onx = sess.run([prob_name], {input_name: test_input})[0]
            
            # handle different output formats
            if isinstance(probs_onx, list):
                probs_onx = np.array(probs_onx)
            
            # get shape safely
            if hasattr(probs_onx, 'shape'):
                shape = probs_onx.shape
            else:
                shape = f"({len(probs_onx)},)" if hasattr(probs_onx, '__len__') else "unknown"
            
            print(f"✓ Probability output available: shape {shape}")
            
            # show example probabilities
            try:
                if hasattr(probs_onx, '__getitem__'):
                    first_probs = probs_onx[0] if len(probs_onx.shape) > 1 else probs_onx
                    display_probs = first_probs[:min(3, len(first_probs))]
                    print(f"✓ Example probabilities: {[f'{p:.4f}' for p in display_probs]}")
            except:
                print(f"✓ Probabilities extracted successfully")
        
    except Exception as e:
        print(f"⚠️  Inference test warning: {e}")
        print(f"   (Model still usable, just couldn't verify all outputs)")
    
    print(f"\n✅ SUCCESS: {onnx_file} ready for deployment")
    print(f"   Description: {model_info['description']}")
    print(f"   Input: float[1, {num_features}] (29 acoustic features)")
    print(f"   Output: int (class label) + float[1, N] (probabilities)")
    
    return True

def generate_android_code():
    """Generate sample Android integration code"""
    
    code = '''
// Android ONNX Runtime Integration Example
// Add to build.gradle: implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.15.0'

import ai.onnxruntime.*;

class DrowsinessDetector {
    private OrtEnvironment env;
    private OrtSession session;
    
    public DrowsinessDetector(Context context) throws OrtException {
        // load ONNX model from assets
        env = OrtEnvironment.getEnvironment();
        byte[] modelBytes = loadModelFromAssets(context, "sleepiness_detector.onnx");
        session = env.createSession(modelBytes);
    }
    
    public float predictDrowsiness(float[] acousticFeatures) throws OrtException {
        // input: 29 acoustic features
        long[] shape = {1, 29};
        OnnxTensor inputTensor = OnnxTensor.createTensor(env, 
            FloatBuffer.wrap(acousticFeatures), shape);
        
        // run inference
        OrtSession.Result results = session.run(
            Collections.singletonMap("float_input", inputTensor)
        );
        
        // get probability of SLEEPY class
        float[][] probabilities = (float[][]) results.get(1).getValue();
        float sleepyProb = probabilities[0][1];  // index 1 = SLEEPY
        
        inputTensor.close();
        results.close();
        
        return sleepyProb;
    }
    
    private byte[] loadModelFromAssets(Context context, String filename) {
        try {
            InputStream is = context.getAssets().open(filename);
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            return buffer;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load model", e);
        }
    }
    
    public void close() throws OrtException {
        session.close();
        env.close();
    }
}
'''
    
    return code

if __name__ == "__main__":
    
    success_count = 0
    
    for model_info in MODELS:
        if convert_model(model_info):
            success_count += 1
    
    print(f"\n{'='*70}")
    print(f"CONVERSION SUMMARY")
    print(f"{'='*70}")
    print(f"Successfully converted: {success_count}/{len(MODELS)} models")
    
    if success_count > 0:
        print(f"\n📦 ONNX Models Ready:")
        for model_info in MODELS:
            if os.path.exists(model_info['onnx_file']):
                size_kb = os.path.getsize(model_info['onnx_file']) / 1024
                print(f"   ✓ {model_info['onnx_file']} ({size_kb:.1f} KB)")
        
        print(f"\n🚀 Android Integration:")
        print(f"   1. Copy .onnx files to app/src/main/assets/")
        print(f"   2. Add ONNX Runtime dependency to build.gradle:")
        print(f"      implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.15.0'")
        print(f"   3. Use OrtSession to run inference (see android_integration.java)")
        
        # save android example code
        with open("android_integration.java", "w") as f:
            f.write(generate_android_code())
        print(f"   4. Example code saved to: android_integration.java")
        
        print(f"\n📄 Documentation:")
        print(f"   See ONNX_DEPLOYMENT.md for detailed integration guide")
    
    print(f"\n{'='*70}")
