# ML Kit discovers these exact manifest registrars through no-argument reflection.
# The pinned firebase-components consumer rule keeps classes but omits constructors;
# R8 full mode otherwise removes them, leaving OCR components unregistered.
-keepclassmembers class com.google.mlkit.common.internal.CommonComponentRegistrar {
    public <init>();
}
-keepclassmembers class com.google.mlkit.vision.common.internal.VisionCommonRegistrar {
    public <init>();
}
-keepclassmembers class com.google.mlkit.vision.text.internal.TextRegistrar {
    public <init>();
}
