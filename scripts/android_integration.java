
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
