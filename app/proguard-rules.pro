# Workers are instantiated by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
