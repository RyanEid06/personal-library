# AndroidJUnitRunner shares this dependency with the target APK. Its startup
# calls require the public API even when application R8 would inline/remove it.
# Applied only with memoraOptimizedTests=true; release and the untouched optimized
# launch candidate retain their normal optimization rules.
-keep class androidx.tracing.Trace { public *; }
