# JustRead has no reflection or serialisation, so R8's defaults are enough.
# Keep line numbers so release crash traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
