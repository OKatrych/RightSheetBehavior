## RightSheetBehavior - a "right" version of the BottomSheetBehavior

[![](https://jitpack.io/v/OKatrych/RightSheetBehavior.svg)](https://jitpack.io/#OKatrych/RightSheetBehavior)

### Installation:
Step 1. Add it in your root build.gradle at the end of repositories:
```
allprojects {
    repositories {
        ...
	maven { url 'https://jitpack.io' }
    }
}
```
Step 2. Add the dependency:
```
dependencies {
    implementation 'com.github.OKatrych:RightSheetBehavior:1.0'
}
```

### Usage:
In XML:
```XML
<FrameLayout
        android:id="@+id/right_sheet"
        android:layout_width="400dp"
        android:layout_height="match_parent"
        app:behavior_peekHeight="50dp"
        app:layout_behavior="@string/right_sheet_behavior"/>
```
In code:
```java
View sheet = findViewById(R.id.right_sheet);
RightSheetBehavior rightSheetBehavior = RightSheetBehavior.from(sheet);
```
Check out the **RightSheetApp** for an example.

## License

    Copyright 2020 Oleksandr Katrych

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
