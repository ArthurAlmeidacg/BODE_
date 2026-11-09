# TensorFlow Lite Object Detection with Gemini AI

An Android application that combines **real-time object detection** using TensorFlow Lite with **Gemini AI image analysis** and **text-to-speech** capabilities. The app provides audio feedback for detected objects and allows users to capture photos for detailed AI-powered image description.

## Features

### Real-Time Object Detection
- **Live object detection** using TensorFlow Lite model EfficientDet
- **Bounding box visualization** for detected objects
- **Configurable confidence threshold** for detection accuracy
- Support for multiple hardware accelerators (CPU, GPU, NNAPI)

### Audio Feedback System
- **Spatial audio cues** indicating object location:
  - Left position: Left audio notification
  - Center position: Center audio notification
  - Right position: Right audio notification
- **500ms cooldown** between audio cues to prevent audio saturation
- Built-in audio management using `SoundPool`

### Gemini AI Integration
- **Triple-tap gesture** to open camera and capture photos
- **Send images to Gemini** for detailed AI analysis
- **Text-to-Speech (TTS)** output of Gemini's image descriptions
- Supports Portuguese and other locales through system language settings

### User Interface
- Clean and intuitive design
- Real-time camera preview with overlay
- Dynamic bounding box display
- Bottom sheet configuration panel for settings adjustment

## Requirements

### Android Requirements
- **Minimum API Level:** 21 (Android 5.0 Lollipop)
- **Target API Level:** 33 or higher
- **Permissions:** Camera access

### Dependencies
- Android AppCompat
- AndroidX (Lifecycle, Fragment, Navigation)
- CameraX
- TensorFlow Lite
- TensorFlow Lite Support Library
- Google Generative AI (Gemini)
- Material Design Components

