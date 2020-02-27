package eu.okatrych.rightsheet;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;

import androidx.annotation.NonNull;
import androidx.annotation.StyleableRes;
import androidx.appcompat.content.res.AppCompatResources;

class CompatUtils {

    /**
     * Returns the {@link ColorStateList} from the given {@link TypedArray} attributes. The resource
     * can include themeable attributes, regardless of API level.
     */
    static ColorStateList getColorStateList(
            @NonNull Context context,
            @NonNull TypedArray attributes,
            @StyleableRes int index
    ) {
        if (attributes.hasValue(index)) {
            int resourceId = attributes.getResourceId(index, 0);
            if (resourceId != 0) {
                ColorStateList value = AppCompatResources.getColorStateList(context, resourceId);
                if (value != null) {
                    return value;
                }
            }
        }

        return attributes.getColorStateList(index);
    }
}
